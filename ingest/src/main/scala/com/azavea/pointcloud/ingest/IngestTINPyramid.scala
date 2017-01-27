package com.azavea.pointcloud.ingest

import com.azavea.pointcloud.ingest.conf.IngestConf

import geotrellis.pointcloud.pipeline._
import geotrellis.pointcloud.spark.io.hadoop._
import geotrellis.pointcloud.spark.triangulation._
import geotrellis.raster.io._
import geotrellis.raster.io.geotiff.GeoTiff
import geotrellis.raster._
import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.hadoop._
import geotrellis.spark.io.index.ZCurveKeyIndexMethod
import geotrellis.spark.io.kryo.KryoRegistrator
import geotrellis.spark.pyramid.Pyramid
import geotrellis.spark.tiling._
import geotrellis.util._
import geotrellis.proj4.CRS

import com.vividsolutions.jts.geom.Coordinate
import org.apache.hadoop.fs.Path
import org.apache.spark.rdd.RDD
import org.apache.spark.serializer.KryoSerializer
import org.apache.spark.{SparkConf, SparkContext}
import spire.syntax.cfor._

import scala.collection.mutable

object IngestTINPyramid {
  def time[T](msg: String)(f: => T): T = {
    val s = System.currentTimeMillis
    val result = f
    val e = System.currentTimeMillis
    val t = "%,d".format(e - s)
    println("=================TIMING RESULT=================")
    println(s"$msg (in $t ms)")
    println("==============================================")
    result
  }

  def main(args: Array[String]): Unit = {
    val opts      = IngestConf.parse(args)
    // val chunkPath = System.getProperty("user.dir") + "/chunks/"

    val conf = new SparkConf()
      .setIfMissing("spark.master", "local[*]")
      .setAppName("PointCloudCount")
      .set("spark.local.dir", "/data/spark")
      .set("spark.serializer", classOf[KryoSerializer].getName)
      .set("spark.kryo.registrator", classOf[KryoRegistrator].getName)

    implicit val sc = new SparkContext(conf)

    try {
      val options = HadoopPointCloudRDD.Options.DEFAULT.copy(
        pipeline =
          Read("", opts.inputCrs) ~
            ReprojectionFilter(opts.destCrs) ~
            opts.maxValue.map { v => RangeFilter(Some(s"Z[0:$v]")) }
      )

      val source = HadoopPointCloudRDD(new Path(opts.inputPath), options).cache()

      val (extent, crs) =
      source
        .map { case (header, _) => (header.projectedExtent3D.extent3d.toExtent, header.crs) }
        .reduce { case ((e1, c), (e2, _)) => (e1.combine(e2), c) }

      val targetCrs = CRS.fromName(opts.destCrs)

      val targetExtent =
        opts.extent match {
          case Some(e) => if (crs.epsgCode != targetCrs.epsgCode) e.reproject(crs, targetCrs) else e
          case _ =>  if (crs.epsgCode != targetCrs.epsgCode) extent.reproject(crs, targetCrs) else extent
        }

      val layoutScheme = if (opts.pyramid || opts.zoomed) ZoomedLayoutScheme(targetCrs) else FloatingLayoutScheme(512)

      val LayoutLevel(zoom, layout) = layoutScheme.levelFor(targetExtent, opts.cellSize)
      val mapTransform = layout.mapTransform
      val kb = KeyBounds(mapTransform(targetExtent))
      val md = TileLayerMetadata[SpatialKey](DoubleConstantNoDataCellType, layout, targetExtent, targetCrs, kb)

      val cut: RDD[(SpatialKey, Array[Coordinate])] =
        source
          .flatMap { case (header, pointClouds) =>
            var lastKey: SpatialKey = null
            val keysToPoints = mutable.Map[SpatialKey, mutable.ArrayBuffer[Coordinate]]()

            for (pointCloud <- pointClouds) {
              val len = pointCloud.length
              cfor(0)(_ < len, _ + 1) { i =>
                val x = pointCloud.getX(i)
                val y = pointCloud.getY(i)
                val z = pointCloud.getZ(i)
                val p = new Coordinate(x, y, z)
                val key = mapTransform(x, y)
                if (key == lastKey) {
                  keysToPoints(lastKey) += p
                } else if (keysToPoints.contains(key)) {
                  keysToPoints(key) += p
                  lastKey = key
                } else {
                  keysToPoints(key) = mutable.ArrayBuffer(p)
                  lastKey = key
                }
              }
            }

            keysToPoints.map { case (k, v) => (k, v.toArray) }
          }
          .reduceByKey({ (p1, p2) => p1 ++ p2 }, opts.numPartitions)

      val tiles: RDD[(SpatialKey, Tile)] =
        TinToDem.withStitch(cut, layout, extent)

      val layer = ContextRDD(tiles, md)

      layer.cache()

      def buildPyramid(zoom: Int, rdd: TileLayerRDD[SpatialKey])
                      (sink: (TileLayerRDD[SpatialKey], Int) => Unit): List[(Int, TileLayerRDD[SpatialKey])] = {
        if (zoom >= opts.minZoom) {
          rdd.cache()
          sink(rdd, zoom)
          val pyramidLevel@(nextZoom, nextRdd) = Pyramid.up(rdd, layoutScheme, zoom)
          pyramidLevel :: buildPyramid(nextZoom, nextRdd)(sink)
        } else {
          sink(rdd, zoom)
          List((zoom, rdd))
        }
      }

      if(opts.persist) {
        val writer = HadoopLayerWriter(opts.catalogPath)
        val attributeStore = writer.attributeStore

        var savedHisto = false
        if (opts.pyramid) {
          buildPyramid(zoom, layer) { (rdd, zoom) =>
            writer
              .write[SpatialKey, Tile, TileLayerMetadata[SpatialKey]](
              LayerId(opts.layerName, zoom),
              rdd,
              ZCurveKeyIndexMethod
            )

            println(s"=============================INGEST ZOOM LVL: $zoom=================================")

            if (!savedHisto) {
              savedHisto = true
              val histogram = rdd.histogram(512)
              attributeStore.write(
                LayerId(opts.layerName, 0),
                "histogram",
                histogram
              )
            }
          }.foreach { case (z, rdd) => rdd.unpersist(true) }
        } else {
          writer
            .write[SpatialKey, Tile, TileLayerMetadata[SpatialKey]](
            LayerId(opts.layerName, 0),
            layer,
            ZCurveKeyIndexMethod
          )

          if (!savedHisto) {
            savedHisto = true
            val histogram = layer.histogram(512)
            attributeStore.write(
              LayerId(opts.layerName, 0),
              "histogram",
              histogram
            )
          }
        }
      }

      opts.testOutput match {
        case Some(to) => GeoTiff(layer.stitch, crs).write(to)
        case _ => if(!opts.persist) layer.count
      }

      layer.unpersist(blocking = false)
      source.unpersist(blocking = false)

    } finally sc.stop()
  }
}

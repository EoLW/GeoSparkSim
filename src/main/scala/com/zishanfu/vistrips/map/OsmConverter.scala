package com.zishanfu.vistrips.map

import com.zishanfu.vistrips.tools.Distance;
import scala.collection.mutable.WrappedArray

import org.apache.spark.sql._
import org.apache.spark.sql.functions._

import com.zishanfu.vistrips.network.Link
import com.vividsolutions.jts.geom.Coordinate
import com.vividsolutions.jts.geom.GeometryFactory
import com.vividsolutions.jts.geom.Point
import org.apache.spark.graphx.Graph
import org.apache.spark.graphx.Edge

object OsmConverter {
  
  val gf = new GeometryFactory()
  //openstreetmap EPSG:3857
  
  private def createLink(id : Long, tail :(Int, Long, Double, Double), head : (Int, Long, Double, Double), 
        speed : Int, driveDirection :Int, lanes : Int) :Link = {
        //openstreetmap <lat, lon>
        //geotools <lon, lat>
        val tailCoor = gf.createPoint(new Coordinate(tail._4, tail._3))
        tailCoor.setUserData(tail._2)
        val headCoor = gf.createPoint(new Coordinate(head._4, head._3))
        headCoor.setUserData(head._2)
        val dist = new Distance().harversineMile(headCoor, tailCoor)
        Link(id, tailCoor, headCoor, dist, speed, driveDirection, lanes)
  }
  
  
  def convertToNetwork(sparkSession : SparkSession, path : String) : Graph[Point, Link]= {
//    val nodesPath = path + "/node.parquet"
//    val waysPath = path + "/way.parquet"
    val localpath = "/home/zishanfu/Downloads"
    
    val nodesPath = localpath + "/node.parquet"
    val waysPath = localpath + "/way.parquet"
    
    val nodesDF = convertNodes(sparkSession, nodesPath)
    val network = convertLinks(sparkSession, nodesDF, waysPath)
    
    val nodeDS = network._1
    val linkDS = network._2
    
    val nodesRDD = nodeDS.rdd.map(node => (node.getUserData.asInstanceOf[Long], node))
    val edgesRDD = linkDS.rdd.map(link => {
            Edge(link.getTail().getUserData.asInstanceOf[Long],
                link.getHead().getUserData.asInstanceOf[Long], 
                link)
        })

        
    val graph = Graph(nodesRDD, edgesRDD)
    
    graph
  }
  
  private def convertNodes(sparkSession : SparkSession, nodesPath : String) : DataFrame = {
    val nodesDF = sparkSession.read.parquet(nodesPath)
    nodesDF.select("id", "latitude", "longitude")
  }
  
  private def convertLinks(sparkSession : SparkSession, nodeDF : DataFrame, waysPath : String) = {
    val linkEncoder = Encoders.kryo[Link]
    val PointEncoder = Encoders.kryo[Point]
    
    val defaultSpeed = 40 //40mph
    val waysDF = sparkSession.read.parquet(waysPath).toDF("id", "tags", "nodes")

    val wayNodesDF = waysDF.select(col("id").as("wayId"), col("tags"), explode(col("nodes")).as("indexedNode"))
        .withColumn("linkId", monotonically_increasing_id())
        
            
    var nodeLinkJoinDF = nodeDF.join(wayNodesDF, col("indexedNode.nodeId") === nodeDF("id"))
        nodeLinkJoinDF.cache()
        
    var nodesInLinksDF = nodeLinkJoinDF.select(col("indexedNode.nodeId").as("id"), col("latitude"), col("longitude")).dropDuplicates()
            
    val wayDF = nodeLinkJoinDF.groupBy(col("wayId"), col("tags"))
        .agg(collect_list(struct(col("indexedNode.index"), col("indexedNode.nodeId"), col("latitude"), col("longitude"))).as("nodes")
        , collect_list(col("linkId")).as("linkIds"))
        
    
    
    var linkDS :Dataset[Link] = wayDF.flatMap((row : Row) => { 
      val id = row.getAs[Long](0)
      val tags = row.getAs[WrappedArray[Row]](1)
      var nodes = row.getAs[WrappedArray[Row]](2).map(r => (r.getInt(0), r.getAs[Long](1), r.getAs[Double](2), r.getAs[Double](3))).array
                      .sortBy(x => x._1)
      
      var links : List[Link] = List.empty[Link]
      var tagsMap = Map.empty[String, String]

      tagsMap = tags.map(r => new String(r.getAs[Array[Byte]]("key")) -> new String(r.getAs[Array[Byte]]("value"))).toMap
      
      var speed = defaultSpeed
      var maxSpeed = tagsMap.get("maxspeed")
      if(!maxSpeed.isEmpty) speed = maxSpeed.get.toInt
      
      var isOneWay = tagsMap.getOrElse("oneway", "no") == "yes"
      isOneWay = tagsMap.getOrElse("junction", "default") == "roundabout"
      val lanes = tagsMap.get("lanes").get.toInt
      isOneWay = if(lanes == 1) true else false

      val driveDirection = if(isOneWay) 1 else 2

      
      var linkIds = row.getAs[WrappedArray[Long]](3).toArray
      
      for (i <- 0 until nodes.length - 1) {
        var tail = nodes(i)
        var head = nodes(i + 1)
        
        links = links :+ createLink(id, tail, head, speed, driveDirection, lanes)
        
      }
            
      links
            
   })(linkEncoder)

        
   var nodeDS = nodesInLinksDF.map((r:Row) =>{
      val point = gf.createPoint(new Coordinate(r.getDouble(2), r.getDouble(1)))
      point.setUserData(r.getLong(0))
      point
   })(PointEncoder)
   
   linkDS = linkDS.flatMap(link => {
     if(link.getDrivingDirection() == 1){
        List(link)
      }else{
        List(
          Link(link.getId(), link.getHead(), link.getTail(), link.getDistance(), link.getSpeed(), 1, link.getLanes()/2),
          Link(link.getId(), link.getTail(), link.getHead(), link.getDistance(), link.getSpeed(), 1, link.getLanes()/2)
        )
      }
   })(linkEncoder)
        
   
   (nodeDS, linkDS)
     

  }
  
  
}
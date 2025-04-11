package project_3

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.apache.spark.graphx._
import org.apache.spark.storage.StorageLevel
import org.apache.log4j.{Level, Logger}

object main{
  val rootLogger = Logger.getRootLogger()
  rootLogger.setLevel(Level.ERROR)

  Logger.getLogger("org.apache.spark").setLevel(Level.WARN)
  Logger.getLogger("org.spark-project").setLevel(Level.WARN)

  /* Luby's Algorithm to create MIS */
  def LubyMIS(g_in: Graph[Int, Int]): Graph[Int, Int] = {
    var g = g_in.mapVertices((id, _) => 0)
    var iteration = 0
    var remaining_vertices = g.vertices.filter { case (_, attr) => attr == 0 }.count()

    while (remaining_vertices >= 1) {
      iteration += 1
      /* Prints remaining active vertices before each iteration */
      println("==================================")
      println("Before iteration " + iteration + ": " + remaining_vertices + " active vertices.")
      println("==================================")

      /* Priotity queue based on randomly assigned double values */
      val priorityGraph = g.mapVertices((id, attr) => {
        val prio = if (attr == 0) scala.util.Random.nextDouble() else -1.0
        (attr, prio)
      })

      val maxNeighborPrio = priorityGraph.aggregateMessages[Double](
        triplet => {
          val (srcState, srcPrio) = triplet.srcAttr
          val (dstState, dstPrio) = triplet.dstAttr
          if (srcState == 0 && dstState == 0) {
            triplet.sendToSrc(dstPrio)
            triplet.sendToDst(srcPrio)
          }
        },
        math.max
      )

      val selected = priorityGraph.vertices.leftOuterJoin(maxNeighborPrio).mapValues {
        case ((state, prio), Some(maxN)) => if (prio > maxN) 1 else 0
        case ((state, prio), None) => 1
      }

      /* Choose which vertices are actually part of the MIS */
      val withMIS = g.joinVertices(selected) {
        case (_, oldState, selectedFlag) =>
          if (oldState == 0 && selectedFlag == 1) 1 else oldState
      }

      val removeNeighbors = withMIS.aggregateMessages[Int](
        triplet => {
          if (triplet.srcAttr == 1 && triplet.dstAttr == 0)
            triplet.sendToDst(-1)
          if (triplet.dstAttr == 1 && triplet.srcAttr == 0)
            triplet.sendToSrc(-1)
        },
        (a, b) => a
      )

      val finalGraph = withMIS.joinVertices(removeNeighbors) {
        case (_, state, removalFlag) =>
          if (state == 0) -1 else state
      }

      g = finalGraph
      remaining_vertices = g.vertices.filter { case (_, attr) => attr == 0 }.count()
    }

    /* Prints iterations */
    println("==================================")
    println("LubyMIS completed in " + iteration + " iterations.")
    println("==================================")
    return g
  }

  /* Function to verified whether latter input is an MIS of the prior input */
  def verifyMIS(g_in: Graph[Int, Int]): Boolean = {

    /* Checks for independence */
    val violations = g_in.triplets.filter(t => t.srcAttr == 1 && t.dstAttr == 1).count()
    if (violations > 0) return false

    /* Checks for maximality */
    val maxCheck = g_in.aggregateMessages[Int](
      triplet => {
        if (triplet.srcAttr == 1 && triplet.dstAttr == -1)
          triplet.sendToDst(1)
        if (triplet.dstAttr == 1 && triplet.srcAttr == -1)
          triplet.sendToSrc(1)
      },
      (a, b) => a + b
    )

    /* Checks for any vertices uncovered as MIS */
    val nonCovered = g_in.vertices
      .filter { case (_, attr) => attr == -1 }
      .leftOuterJoin(maxCheck)
      .filter { case (_, (_, opt)) => opt.getOrElse(0) == 0 }
      .count()

    return nonCovered == 0
  }

  def main(args: Array[String]) {

    val conf = new SparkConf().setAppName("project_3")
    val sc = new SparkContext(conf)
    val spark = SparkSession.builder.config(conf).getOrCreate()
/* You can either use sc or spark */

    if(args.length == 0) {
      println("Usage: project_3 option = {compute, verify}")
      sys.exit(1)
    }
    if(args(0)=="compute") {
      if(args.length != 3) {
        println("Usage: project_3 compute graph_path output_path")
        sys.exit(1)
      }
      val startTimeMillis = System.currentTimeMillis()
      val edges = sc.textFile(args(1)).map(line => {val x = line.split(","); Edge(x(0).toLong, x(1).toLong , 1)} )
      val g = Graph.fromEdges[Int, Int](edges, 0, edgeStorageLevel = StorageLevel.MEMORY_AND_DISK, vertexStorageLevel = StorageLevel.MEMORY_AND_DISK)
      val g2 = LubyMIS(g)

      val endTimeMillis = System.currentTimeMillis()
      val durationSeconds = (endTimeMillis - startTimeMillis) / 1000
      println("==================================")
      println("Luby's algorithm completed in " + durationSeconds + "s.")
      println("==================================")

      val g2df = spark.createDataFrame(g2.vertices)
      g2df.coalesce(1).write.format("csv").mode("overwrite").save(args(2))
    }
    else if(args(0)=="verify") {
      if(args.length != 3) {
        println("Usage: project_3 verify graph_path MIS_path")
        sys.exit(1)
      }

      val edges = sc.textFile(args(1)).map(line => {val x = line.split(","); Edge(x(0).toLong, x(1).toLong , 1)} )
      val vertices = sc.textFile(args(2)).map(line => {val x = line.split(","); (x(0).toLong, x(1).toInt) })
      val g = Graph[Int, Int](vertices, edges, edgeStorageLevel = StorageLevel.MEMORY_AND_DISK, vertexStorageLevel = StorageLevel.MEMORY_AND_DISK)

      val ans = verifyMIS(g)
      if(ans)
        println("Yes")
      else
        println("No")
    }
    else
    {
        println("Usage: project_3 option = {compute, verify}")
        sys.exit(1)
    }
  }
}

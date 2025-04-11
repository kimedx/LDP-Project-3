error id: vertices.
file://<WORKSPACE>/src/main/scala/project_3/main.scala
empty definition using pc, found symbol in pc: vertices.
empty definition using semanticdb
empty definition using fallback
non-local guesses:

offset: 1693
uri: file://<WORKSPACE>/src/main/scala/project_3/main.scala
text:
```scala
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

  def LubyMIS(g_in: Graph[Int, Int]): Graph[Int, Int] = {
    var g = g_in.mapVertices((id, _) => 0) // 0 = undecided, 1 = in MIS, -1 = removed
    var iteration = 0
    var remaining_vertices = g.vertices.filter { case (_, attr) => attr == 0 }.count()

    while (remaining_vertices >= 1) {
      iteration += 1
      println(s"--- Iteration $iteration: $remaining_vertices active vertices ---")

      // Step 1: Assign random priorities to undecided vertices
      val priorityGraph = g.mapVertices((id, attr) => {
        val prio = if (attr == 0) scala.util.Random.nextDouble() else -1.0
        (attr, prio)
      })

      // Step 2: Max priority among neighbors
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

      // Step 3: Select local maxima
      val selected = priorityGraph.@@vertices.leftOuterJoin(maxNeighborPrio).mapValues {
        case ((state, prio), Some(maxN)) => if (prio > maxN) 1 else 0
        case ((state, prio), None) => 1
      }

      // Step 4: Update selected into MIS
      val withMIS = g.joinVertices(selected) {
        case (_, oldState, selectedFlag) =>
          if (oldState == 0 && selectedFlag == 1) 1 else oldState
      }

      // Step 5: Remove neighbors of new MIS members
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

    println(s"--- LubyMIS completed in $iteration iterations ---")
    return g
  }

  def verifyMIS(g_in: Graph[Int, Int]): Boolean = {
    // To Implement
    // 1. Check for independence: no two adjacent vertices in MIS (i.e., both labeled 1)
    val violations = g_in.triplets.filter(t => t.srcAttr == 1 && t.dstAttr == 1).count()
    if (violations > 0) return false

    // 2. Check for maximality: every vertex not in MIS should have at least one neighbor in MIS
    val maxCheck = g_in.aggregateMessages[Int](
      triplet => {
        if (triplet.srcAttr == 1 && triplet.dstAttr == -1)
          triplet.sendToDst(1)
        if (triplet.dstAttr == 1 && triplet.srcAttr == -1)
          triplet.sendToSrc(1)
      },
      (a, b) => a + b
    )

    // Vertices not in MIS that do NOT have a neighbor in MIS
    val nonCovered = g_in.vertices
      .filter { case (vid, attr) => attr == -1 } // not in MIS
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

```


#### Short summary: 

empty definition using pc, found symbol in pc: vertices.
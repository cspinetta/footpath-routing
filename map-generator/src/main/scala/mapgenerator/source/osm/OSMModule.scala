package mapgenerator.source.osm

import enums.StreetTraversalPermission
import mapgenerator.source.osm.model._

import scala.collection.immutable.ListSet
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

case class OSMModule(nodes: Seq[OSMNode], ways: Seq[Way], relations: Seq[Relation]) {

  type AreasByNodeId = mutable.ListMap[Long, mutable.Set[Way]] with mutable.MultiMap[Long, Way]

  val areaWayIds: ArrayBuffer[Long] = ArrayBuffer.empty
  val relationById: mutable.ListMap[Long, Relation] = mutable.ListMap.empty

  val areasByNodeId: AreasByNodeId = new mutable.ListMap[Long, mutable.Set[Way]] with mutable.MultiMap[Long, Way]

  var walkableAreas: ArrayBuffer[Area] = ArrayBuffer.empty
  var parkAndRideAreas: ArrayBuffer[Area] = ArrayBuffer.empty
  var bikeParkingAreas: ArrayBuffer[Area] = ArrayBuffer.empty

  val processedAreas: mutable.HashSet[OSMElement] = mutable.HashSet.empty

  // 1 Create relations
  createRelations()

  // 2 Create Ways
  val (
    streetWays: mutable.ArrayBuffer[Way],
    areaWays: Vector[Way],
    singleAreaWays: Vector[Way]) = partitionWays

  // 3 Save nodes from ways
  val nodesFromStreetWays: Vector[Long] = streetWays.flatMap(_.nodeIds.toVector).toVector
  val nodesFromAreaWays: Vector[Long] = areaWays.flatMap(_.nodeIds)

  // 4 Save other nodes
  val bikeRentalNodes: List[OSMNode] = nodes.filter(_.isBikeRental).toList
  val bikeParkingNodes: List[OSMNode] = nodes.filter(node ⇒ node.isBikeParking) toList
  val otherNodes: ListSet[OSMNode] = ListSet(nodes.filter(node ⇒
    nodesFromStreetWays.contains(node.id) || nodesFromAreaWays.contains(node.id) || node.isStop): _*)

  val otherNodeIds: Set[Long] = otherNodes.map(_.id)

  // 5 Create Area Ways
  processMultipolygonRelations()
  processSingleWayAreas()

  //************************* 1. get relations

  private def createRelations(): Unit = {

    relations.foreach { relation ⇒
      val tags: Map[String, String] = relation.tags
      if (tags.get("type").contains("multipolygon") &&
        (relation.isOsmEntityRoutable || relation.isParkAndRide)) {

        // OSM MultiPolygons are ferociously complicated, and in fact cannot be processed
        // without reference to the ways that compose them. Accordingly, we will merely
        // mark the ways for preservation here, and deal with the details once we have
        // the ways loaded.
        if (relation.isRoutableWay || relation.isParkAndRide) {

          for (member ← relation.members) {
            areaWayIds += member.ref
          }
          //          applyLevelsForWay(relation)
          relationById += ((relation.id, relation))
        }
      } else if (tags.get("type").contains("restriction") ||
        (tags.get("type").contains("route") && tags.get("route").contains("road")) ||
        (tags.get("type").contains("multipolygon") && relation.isOsmEntityRoutable) ||
        tags.get("type").contains("level_map") ||
        (tags.get("type").contains("public_transport") && tags.get("public_transport").contains("stop_area"))) {

        relationById += ((relation.id, relation))
      }

    }

  }

  //************************* 2. get ways

  /**
   * Parse all the ways and partition them between street and area ways
   *
   * @return (street way , area way)
   */
  private def partitionWays: (mutable.ArrayBuffer[Way], Vector[Way], Vector[Way]) = {

    val streetWayBuilder: mutable.Builder[Way, mutable.ArrayBuffer[Way]] = mutable.ArrayBuffer.newBuilder[Way]
    val areaWayBuilder = Vector.newBuilder[Way]

    val singleWayAreasBuilder = Vector.newBuilder[Way]

    ways foreach { osmWay ⇒

      if (areaWayIds.contains(osmWay.id))
        areaWayBuilder += osmWay

      if (osmWay.isRoutableWay || osmWay.isParkAndRide || osmWay.isBikeParking) {
        if (osmWay.isAreaWay) {

          if (!areaWayIds.contains(osmWay.id)) { // if areaWayIds doesn't contain its, it's not part of a relation
            singleWayAreasBuilder += osmWay
            areaWayBuilder += osmWay
            areaWayIds += osmWay.id
            for (nodeId ← osmWay.nodeIds) {
              areasByNodeId addBinding (nodeId, osmWay)
            }
          }
        } else streetWayBuilder += osmWay
      }
    }
    (streetWayBuilder.result(), areaWayBuilder.result(), singleWayAreasBuilder.result())

  }

  private def processMultipolygonRelations(): Unit = {
    // RELATION //todo: labels is not supported
    for ((id, relation) ← relationById) {
      val tags = relation.tags

      if (id == 4547633) {
        println("es este relation!!")
      }

      val thisAreaWays: Map[Long, Way] = relation.members.flatMap(member ⇒ areaWays.find(_.id == member.ref).map(way ⇒ member.ref -> way).toList).toMap

      if (thisAreaWays.size == relation.members.size &&
        !processedAreas.contains(relation) &&
        (tags.get("type").contains("multipolygon") &&
          (relation.isOsmEntityRoutable || relation.isParkAndRide)) &&
          relation.members.forall(member ⇒
            thisAreaWays(member.ref).nodeIds.forall(otherNodeIds.contains))) {

        val innerWays: mutable.Builder[Way, Vector[Way]] = Vector.newBuilder[Way]
        val outerWays: mutable.Builder[Way, Vector[Way]] = Vector.newBuilder[Way]

        for (member ← relation.members) {

          val role: String = member.role

          val way = thisAreaWays(member.ref)

          for (nodeId ← way.nodeIds) {
            areasByNodeId addBinding (nodeId, way)
          }

          if (role == "inner") {
            innerWays += way
          } else if (role == "outer") {
            outerWays += way
          } else {
            println("Unexpected role " + role + " in multipolygon")
          }

        }
        processedAreas += relation
        createArea(relation, outerWays.result(), innerWays.result(), otherNodes)
        for (member ← relation.members) {
          if ("way" == member.`type`) {
            val streetWayIndex: Int = streetWays.indexWhere(_.id == member.ref)

            if (streetWayIndex >= 0) {
              val streetWay: Way = streetWays(streetWayIndex)
              for (
                tag ← List("highway", "name", "ref") if relation.tags.contains(tag) && !streetWay.tags.contains(tag)
              ) {
                val streetWay: Way = streetWays(streetWayIndex)
                val newStreetWay = streetWay.copy(tags = tags + (tag -> relation.tags(tag)))
                streetWays.update(streetWayIndex, newStreetWay)
              }
              if (relation.tags.get("railway").contains("platform") && !streetWay.tags.contains("railway")) {
                streetWays.update(streetWayIndex, streetWay.copy(tags = tags + ("railway" -> relation.tags("railway"))))
              }
              if (relation.tags.get("public_transport").contains("platform") && !streetWay.tags.contains("public_transport")) {
                streetWays.update(streetWayIndex, streetWay.copy(tags = tags + ("public_transport" -> relation.tags("public_transport"))))
              }
            }
          }
        }
      }

    }
  }

  private def processSingleWayAreas(): Unit = {
    for {
      areaWay ← singleAreaWays
      if !processedAreas.contains(areaWay) &&
        areaWay.nodeIds.forall(nodeId ⇒ otherNodeIds.contains(nodeId))
    } {
      createArea(areaWay, Vector(areaWay), Vector(), otherNodes)
    }
  }

  private def createArea(osmElement: OSMElement, outerWays: Vector[Way],
    innerWays: Vector[Way], otherNodes: ListSet[OSMNode]) = {

    lazy val areaOpt = Area(osmElement, outerWays, innerWays, otherNodes)

    val permissions = getPermissionsForEntity(osmElement, StreetTraversalPermission.PEDESTRIAN_AND_BICYCLE)

    if (osmElement.isOsmEntityRoutable && permissions != StreetTraversalPermission.NONE) {
      areaOpt.map(area ⇒ walkableAreas += area)
    }

    // Please note: the same area can be both car P+R AND bike park.
    if (osmElement.isParkAndRide) {
      areaOpt.map(area ⇒ parkAndRideAreas += area)
    }
    if (osmElement.isBikeParking) {
      areaOpt.map(area ⇒ bikeParkingAreas += area)
    }
  }

  private def getPermissionsForEntity(osmElement: OSMElement,
    defPermission: StreetTraversalPermission): StreetTraversalPermission = {
    var partialPermission: StreetTraversalPermission =
      if (osmElement.isGeneralAccessDenied) {
        var partialPerm = StreetTraversalPermission.NONE

        if (osmElement.isMotorcarExplicitlyAllowed || osmElement.isMotorVehicleExplicitlyAllowed) {
          partialPerm = partialPerm.add(StreetTraversalPermission.CAR)
        }
        if (osmElement.isBicycleExplicitlyAllowed) {
          partialPerm = partialPerm.add(StreetTraversalPermission.BICYCLE)
        }
        if (osmElement.isPedestrianExplicitlyAllowed) {
          partialPerm = partialPerm.add(StreetTraversalPermission.PEDESTRIAN)
        }
        partialPerm
      } else {
        defPermission
      }

    if (osmElement.isMotorcarExplicitlyDenied || osmElement.isMotorVehicleExplicitlyDenied) {
      partialPermission = partialPermission.remove(StreetTraversalPermission.CAR)
    } else if (osmElement.isMotorcarExplicitlyAllowed || osmElement.isMotorVehicleExplicitlyAllowed) {
      partialPermission = partialPermission.add(StreetTraversalPermission.CAR)
    }

    if (osmElement.isBicycleExplicitlyDenied) {
      partialPermission = partialPermission.remove(StreetTraversalPermission.BICYCLE)
    } else if (osmElement.isBicycleExplicitlyAllowed) {
      partialPermission = partialPermission.add(StreetTraversalPermission.BICYCLE)
    }

    if (osmElement.isPedestrianExplicitlyDenied) {
      partialPermission = partialPermission.remove(StreetTraversalPermission.PEDESTRIAN)
    } else if (osmElement.isPedestrianExplicitlyAllowed) {
      partialPermission = partialPermission.add(StreetTraversalPermission.PEDESTRIAN)
    }

    if (osmElement.isUnderConstruction) {
      partialPermission = StreetTraversalPermission.NONE
    }

    Option(partialPermission).getOrElse {
      println(s"getPermissionsForEntity finaliza con partialPermission en null ... que raro!!!")
      defPermission
    }
  }

}

object OSMModule {
  def apply(reader: OSMReader): OSMModule = new OSMModule(reader.loadNodes, reader.loadWays, reader.loadRelations)
}

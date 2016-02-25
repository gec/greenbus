/**
 * Copyright 2011-2016 Green Energy Corp.
 *
 * Licensed to Green Energy Corp (www.greenenergycorp.com) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. Green Energy
 * Corp licenses this file to you under the GNU Affero General Public License
 * Version 3.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.gnu.org/licenses/agpl.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.greenbus.integration.auth

import io.greenbus.client.service.proto.Model
import io.greenbus.client.service.proto.Model.StoredValue
import io.greenbus.loader.set.{ NoCalculation, SimpleValue, NamedEntId, EntityFields }
import io.greenbus.loader.set.Mdl._

object AuthModelBuilder {

  def storedValueString(v: String): StoredValue = StoredValue.newBuilder().setStringValue(v).build()

  def buildConfigModel(): FlatModelFragment = {

    val edges = Vector.newBuilder[(String, String, String)]

    val rootA = EntityDesc(EntityFields(NamedEntId("RootA"), Set("Root", "RootTypeA"), Seq(("keyA", SimpleValue(storedValueString("valueA"))))))

    val equipAA = EntityDesc(EntityFields(NamedEntId("EquipAA"), Set("Equip", "EquipTypeA"), Seq(("keyA", SimpleValue(storedValueString("valueA"))))))
    val equipAB = EntityDesc(EntityFields(NamedEntId("EquipAB"), Set("Equip", "EquipTypeA", "EquipTypeB"), Seq(("keyA", SimpleValue(storedValueString("valueA"))))))
    edges += (("RootA", "owns", "EquipAA"))
    edges += (("RootA", "owns", "EquipAB"))

    val pointAAA = PointDesc(EntityFields(NamedEntId("PointAAA"), Set("PointTypeA"), Seq()), Model.PointCategory.ANALOG, "unit1", NoCalculation)
    val pointAAB = PointDesc(EntityFields(NamedEntId("PointAAB"), Set("PointTypeA"), Seq()), Model.PointCategory.ANALOG, "unit1", NoCalculation)
    val commandAAA = CommandDesc(EntityFields(NamedEntId("CommandAAA"), Set("CommandTypeA"), Seq()), "commandAAA", Model.CommandCategory.CONTROL)
    edges += (("EquipAA", "owns", "PointAAA"))
    edges += (("EquipAA", "owns", "PointAAB"))
    edges += (("EquipAA", "owns", "CommandAAA"))

    val pointABA = PointDesc(EntityFields(NamedEntId("PointABA"), Set("PointTypeA"), Seq()), Model.PointCategory.ANALOG, "unit1", NoCalculation)
    val pointABB = PointDesc(EntityFields(NamedEntId("PointABB"), Set("PointTypeA"), Seq()), Model.PointCategory.ANALOG, "unit1", NoCalculation)
    val commandABA = CommandDesc(EntityFields(NamedEntId("CommandABA"), Set("CommandTypeA"), Seq()), "commandABA", Model.CommandCategory.CONTROL)
    edges += (("EquipAB", "owns", "PointABA"))
    edges += (("EquipAB", "owns", "PointABB"))
    edges += (("EquipAB", "owns", "CommandABA"))

    val rootB = EntityDesc(EntityFields(NamedEntId("RootB"), Set("Root", "RootTypeB"), Seq(("keyA", SimpleValue(storedValueString("valueA"))), ("keyB", SimpleValue(storedValueString("valueB"))))))

    val pointBA = PointDesc(EntityFields(NamedEntId("PointBA"), Set("PointTypeA"), Seq()), Model.PointCategory.ANALOG, "unit1", NoCalculation)
    val pointBB = PointDesc(EntityFields(NamedEntId("PointBB"), Set("PointTypeA"), Seq()), Model.PointCategory.ANALOG, "unit1", NoCalculation)
    val commandBA = CommandDesc(EntityFields(NamedEntId("CommandBA"), Set("CommandTypeA"), Seq()), "commandAAA", Model.CommandCategory.CONTROL)
    edges += (("RootB", "owns", "PointBA"))
    edges += (("RootB", "owns", "PointBB"))
    edges += (("RootB", "owns", "CommandBA"))

    val endpointA = EndpointDesc(EntityFields(NamedEntId("EndpointA"), Set("EndpointTypeA"), Seq()), "protocolA")
    val endpointB = EndpointDesc(EntityFields(NamedEntId("EndpointB"), Set("EndpointTypeA"), Seq()), "protocolA")
    edges += (("EndpointA", "source", "PointAAA"))
    edges += (("EndpointA", "source", "PointAAB"))
    edges += (("EndpointA", "source", "CommandAAA"))
    edges += (("EndpointA", "source", "PointABA"))
    edges += (("EndpointA", "source", "PointABB"))
    edges += (("EndpointA", "source", "CommandABA"))

    val edgeDescs = edges.result().map { case (p, r, c) => EdgeDesc(NamedEntId(p), r, NamedEntId(c)) }

    val equipment = Seq(rootA, rootB, equipAA, equipAB)
    val points = Seq(pointAAA, pointAAB, pointABA, pointABB, pointBA, pointBB)
    val commands = Seq(commandAAA, commandABA, commandBA)
    val endpoints = Seq(endpointA, endpointB)

    FlatModelFragment(equipment, points, commands, endpoints, edgeDescs)
  }
}

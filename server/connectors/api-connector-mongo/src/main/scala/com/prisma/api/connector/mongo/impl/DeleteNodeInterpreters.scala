package com.prisma.api.connector.mongo.impl

import com.prisma.api.connector._
import com.prisma.api.connector.mongo.database.{MongoActionsBuilder, SequenceAction}
import com.prisma.api.connector.mongo.{NestedDatabaseMutactionInterpreter, TopLevelDatabaseMutactionInterpreter}
import com.prisma.api.schema.APIErrors
import com.prisma.api.schema.APIErrors.NodesNotConnectedError
import com.prisma.gc_values.IdGCValue

import scala.concurrent.ExecutionContext

case class DeleteNodeInterpreter(mutaction: TopLevelDeleteNode)(implicit val ec: ExecutionContext) extends TopLevelDatabaseMutactionInterpreter {

  override def mongoAction(mutationBuilder: MongoActionsBuilder) = {
    for {
      nodeOpt <- mutationBuilder.getNodeByWhere(mutaction.where, SelectedFields.all(mutaction.model))
      node <- nodeOpt match {
               case Some(node) =>
                 for {
//            _ <- performCascadingDelete(mutationBuilder, mutaction.where.model, node.id)
                   _ <- checkForRequiredRelationsViolations(mutationBuilder, node.id)
                   _ <- mutationBuilder.deleteNodeById(mutaction.where.model, node.id)
                 } yield node
               case None =>
                 throw APIErrors.NodeNotFoundForWhereError(mutaction.where)
             }
    } yield MutactionResults(Vector(DeleteNodeResult(node.id, node, mutaction)))
  }

  private def checkForRequiredRelationsViolations(mutationBuilder: MongoActionsBuilder, id: IdGCValue) = {
    val fieldsWhereThisModelIsRequired = mutaction.model.schema.fieldsWhereThisModelIsRequired(mutaction.where.model)
    val actions                        = fieldsWhereThisModelIsRequired.map(field => mutationBuilder.errorIfNodeIsInRelation(id, field)).toVector
    SequenceAction(actions)
  }
}

case class NestedDeleteNodeInterpreter(mutaction: NestedDeleteNode)(implicit val ec: ExecutionContext) extends NestedDatabaseMutactionInterpreter {

  val schema      = mutaction.project.schema
  val parentField = mutaction.relationField
  val parent      = mutaction.relationField.model
  val child       = mutaction.relationField.relatedModel_!

  override def mongoAction(mutationBuilder: MongoActionsBuilder, parentId: IdGCValue) = {
    for {
      childId <- getChildId(mutationBuilder, parentId)
      _       <- mutationBuilder.ensureThatNodesAreConnected(parentField, childId, parentId)
//      _       <- performCascadingDelete(mutationBuilder, child, childId)
      _ <- checkForRequiredRelationsViolations(mutationBuilder, childId)
      _ <- mutationBuilder.deleteNodeById(child, childId)
      _ <- mutationBuilder.deleteRelationRowByChildIdAndParentId(parentField, childId, parentId)
    } yield MutactionResults(Vector.empty)
  }

  private def getChildId(mutationBuilder: MongoActionsBuilder, parentId: IdGCValue) = {
    mutaction.where match {
      case Some(where) =>
        mutationBuilder.getNodeIdByWhere(where).map {
          case Some(id) => id
          case None     => throw APIErrors.NodeNotFoundForWhereError(where)
        }
      case None =>
        mutationBuilder.getNodeIdByParentId(parentField, parentId).map {
          case Some(id) => id
          case None =>
            throw NodesNotConnectedError(
              relation = parentField.relation,
              parent = parentField.model,
              parentWhere = Some(NodeSelector.forId(parent, parentId)),
              child = parentField.relatedModel_!,
              childWhere = None
            )
        }
    }
  }

  private def checkForRequiredRelationsViolations(mutationBuilder: MongoActionsBuilder, parentId: IdGCValue) = {
    val fieldsWhereThisModelIsRequired = mutaction.project.schema.fieldsWhereThisModelIsRequired(mutaction.relationField.relatedModel_!)
    val actions                        = fieldsWhereThisModelIsRequired.map(field => mutationBuilder.errorIfNodeIsInRelation(parentId, field)).toVector
    SequenceAction(actions)
  }
}

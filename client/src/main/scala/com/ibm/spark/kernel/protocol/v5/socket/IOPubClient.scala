/*
 * Copyright 2014 IBM Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ibm.spark.kernel.protocol.v5.socket

import akka.actor.Actor
import akka.zeromq.ZMQMessage
import com.ibm.spark.comm.{CommStorage, CommRegistrar, ClientCommWriter}
import com.ibm.spark.kernel.protocol.v5.MessageType.MessageType
import com.ibm.spark.kernel.protocol.v5.Utilities._
import com.ibm.spark.kernel.protocol.v5.client.execution.DeferredExecutionManager
import com.ibm.spark.kernel.protocol.v5.content._
import com.ibm.spark.kernel.protocol.v5.{ActorLoader, KMBuilder, KernelMessage, MessageType}
import com.ibm.spark.utils.LogLike

import scala.util.Failure

/**
 * The client endpoint for IOPub messages specified in the IPython Kernel Spec
 * @param socketFactory A factory to create the ZeroMQ socket connection
 */
class IOPubClient(
  socketFactory: ClientSocketFactory, actorLoader: ActorLoader,
  commRegistrar: CommRegistrar, commStorage: CommStorage
) extends Actor with LogLike {
  private val PARENT_HEADER_NULL_MESSAGE = "Parent Header was null in Kernel Message."
  private val socket = socketFactory.IOPubClient(context.system, self)
  logger.info("Created IOPub socket")

  /**
   * Constructs and returns a map directing message types to functions.
   *
   * @param kernelMessage The kernel message used to generate the map
   *
   * @return The map of
   */
  private def getMessageMap(kernelMessage: KernelMessage) = Map[String, () => Unit](
    ExecuteResult.toTypeString -> { () =>
      receiveKernelMessage(kernelMessage, receiveExecuteResult(_, kernelMessage))
    },
    StreamContent.toTypeString -> { () =>
      receiveKernelMessage(kernelMessage, receiveStreamMessage(_, kernelMessage))
    },
    CommOpen.toTypeString -> { () =>
      receiveKernelMessage(kernelMessage, receiveCommOpen(_, kernelMessage))
    },
    CommMsg.toTypeString -> { () =>
      receiveKernelMessage(kernelMessage, receiveCommMsg(_, kernelMessage))
    },
    CommClose.toTypeString -> { () =>
      receiveKernelMessage(kernelMessage, receiveCommClose(_, kernelMessage))
    }
  )


  private def receiveKernelMessage(
    kernelMessage: KernelMessage, func: (String) => Unit
  ): Unit = {
    if(kernelMessage.parentHeader != null){
      func(kernelMessage.parentHeader.msg_id)
    } else {
      logger.warn("Received message with null parent header.")
      logger.debug(s"Kernel message is: $kernelMessage")
      sender().forward(Failure(new RuntimeException(PARENT_HEADER_NULL_MESSAGE)))
    }
  }

  private def receiveStreamMessage(
    parentHeaderId:String, kernelMessage: KernelMessage
  ): Unit = {
    // look up callback in CallbackMap based on msg_id and invoke
    val optionalDE = DeferredExecutionManager.get(parentHeaderId)
    optionalDE match {
      case Some(de) => parseAndHandle(kernelMessage.contentString,
        StreamContent.streamContentReads,
        (streamContent: StreamContent) => de.emitStreamContent(streamContent))
      case None =>
        logger.warn(s"No deferred execution found for id $parentHeaderId")
    }
  }

  private def receiveExecuteResult(
    parentHeaderId:String, kernelMessage: KernelMessage
  ): Unit = {
    // look up callback in CallbackMap based on msg_id and invoke
    val optionalDE = DeferredExecutionManager.get(parentHeaderId)
    optionalDE match {
      case Some(de) => parseAndHandle(kernelMessage.contentString,
        ExecuteResult.executeResultReads,
        (executeResult: ExecuteResult) => de.resolveResult(executeResult))
      case None =>
        logger.warn(s"No deferred execution found for id $parentHeaderId")
    }
  }

  private def receiveCommOpen(
    parentHeaderId:String, kernelMessage: KernelMessage
  ): Unit = {
    parseAndHandle(
      kernelMessage.contentString,
      CommOpen.commOpenReads,
      (commOpen: CommOpen) => {
        val targetName = commOpen.target_name
        val commId = commOpen.comm_id

        commStorage.getTargetCallbacks(targetName) match {
          case None             =>
          case Some(callbacks)  =>
            val commWriter = new ClientCommWriter(
              actorLoader, KMBuilder(), commId)
            callbacks.executeOpenCallbacks(
              commWriter, commId, targetName, commOpen.data)
        }
      }
    )
  }

  private def receiveCommMsg(
    parentHeaderId:String, kernelMessage: KernelMessage
  ): Unit = {
    parseAndHandle(
      kernelMessage.contentString,
      CommMsg.commMsgReads,
      (commMsg: CommMsg) => {
        val commId = commMsg.comm_id

        commStorage.getCommIdCallbacks(commId) match {
          case None             =>
          case Some(callbacks)  =>
            val commWriter = new ClientCommWriter(
              actorLoader, KMBuilder(), commId)
            callbacks.executeMsgCallbacks(commWriter, commId, commMsg.data)
        }
      }
    )
  }

  private def receiveCommClose(
    parentHeaderId:String, kernelMessage: KernelMessage
  ): Unit = {
    parseAndHandle(
      kernelMessage.contentString,
      CommClose.commCloseReads,
      (commClose: CommClose) => {
        val commId = commClose.comm_id

        commStorage.getCommIdCallbacks(commId) match {
          case None             =>
          case Some(callbacks)  =>
            val commWriter = new ClientCommWriter(
              actorLoader, KMBuilder(), commId)
            callbacks.executeCloseCallbacks(commWriter, commId, commClose.data)
        }
      }
    )
  }

  override def receive: Receive = {
    case message: ZMQMessage =>
      // convert to KernelMessage using implicits in v5
      logger.debug("Received IOPub kernel message.")
      val kernelMessage: KernelMessage = message
      logger.trace(s"Kernel message is $kernelMessage")
      val messageTypeString = kernelMessage.header.msg_type

      val messageMap = getMessageMap(kernelMessage)

      if (messageMap.contains(messageTypeString)) {
        messageMap(messageTypeString)()
      } else {
        logger.warn(s"Received unhandled MessageType $messageTypeString")
      }
  }
}

package com.itmo.microservices.demo.bombardier.stages

import com.itmo.microservices.demo.bombardier.flow.CoroutineLoggingFactory
import com.itmo.microservices.demo.bombardier.external.ExternalServiceApi
import java.util.*
import kotlin.random.Random

class OrderCollectingStage(private val externalServiceApi: ExternalServiceApi) : TestStage {
    companion object {
        val log = CoroutineLoggingFactory.getLogger(OrderCollectingStage::class.java)
    }

    override suspend fun run(): TestStage.TestContinuationType {
        log.info("Adding items to order ${testCtx().orderId}")
        val itemIds = mutableSetOf<UUID>()
        repeat(Random.nextInt(50)) {
            val itemToAdd = externalServiceApi.getAvailableItems(testCtx().userId!!).random().also { // todo should not to do on each addition but! we can randomise it
                itemIds.add(it.id)
            }

            val amount = Random.nextInt(20)
            externalServiceApi.putItemToOrder(testCtx().userId!!, testCtx().orderId!!, itemToAdd.id, amount)

            val resultAmount = externalServiceApi.getOrder(testCtx().userId!!, testCtx().orderId!!).itemsMap
                .filter { it.key.id == itemToAdd.id }.values
                .firstOrNull()

            if (resultAmount == null || resultAmount != amount) {
                log.error(
                    "Item was not added to the order ${testCtx().orderId}. " +
                            "Expected amount: $amount. Found: $resultAmount"
                )
                return TestStage.TestContinuationType.FAIL
            }
        }

        val finalNumOfItems = externalServiceApi.getOrder(testCtx().userId!!, testCtx().orderId!!).itemsMap.size
        if (finalNumOfItems != itemIds.size) {
            log.error("Added number of items ($finalNumOfItems) doesn't match expected (${itemIds.size})")
            return TestStage.TestContinuationType.FAIL

        }

        log.info("Successfully added ${itemIds.size} items to order ${testCtx().orderId}")
        return TestStage.TestContinuationType.CONTINUE
    }

}
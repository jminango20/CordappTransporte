package com.template

import com.template.flows.*
import com.template.states.*
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkNotarySpec
import net.corda.testing.node.StartedMockNode
import org.jgroups.util.Util.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals


class FlowTests {

    private val mockNetwork = MockNetwork(
        listOf("com.template"),
        notarySpecs = listOf(MockNetworkNotarySpec(CordaX500Name("Notary","London","GB")))
    )
    val retail = mockNetwork.createNode(CordaX500Name("Retail","London","GB"))
    val distributor = mockNetwork.createNode(CordaX500Name("Distributor","London","GB"))
    val producer = mockNetwork.createNode(CordaX500Name("Producer","London","GB"))
    val transporter = mockNetwork.createNode(CordaX500Name("Transporter","London","GB"))

    @Before
    fun setup() = mockNetwork.runNetwork()

    @After
    fun tearDown() = mockNetwork.stopNodes()


    @Test
    fun `Full happy case`() {

        // PRODUCER -> DISTRIBUTOR -> PRODUCER -> TRANSPORTER -> DISTRIBUTOR

        //PRODUCER
        //Producer: Add Product
        val product = Product(
            producer = producer.party(),
            type = Product.Type.A
        )
        producerAddProduct(product)

        //DISTRIBUTOR -> PRODUCER -> TRANSPORTER -> DISTRIBUTOR
        val distributorPurchaseOrderStateID = createPurchaseOrder(
            product = product,
            buyer = distributor,
            seller = producer,
            valueInCents = 1000
        )

        //Producer: Create Delivery
        val distributorDeliveryOrderStateID = createDeliveryOrder(
            purchaseOrderStateID = distributorPurchaseOrderStateID,
            product = product,
            buyer = distributor,
            seller = producer,
            deliveryCompany = transporter
        )

        acceptDeliveryOrder(
            deliveryOrderStateID = distributorDeliveryOrderStateID,
            buyer = distributor,
            seller = producer,
            deliveryCompany = transporter
        )

        receiveDeliverOrder(
            product = product,
            deliveryOrderStateID = distributorDeliveryOrderStateID,
            buyer = distributor,
            seller = producer,
            deliveryCompany = transporter
        )

        // RETAIL -> DISTRIBUTOR -> TRANSPORTER -> RETAIL
        val retailPurchaseOrderStateID = createPurchaseOrder(
            product = product,
            buyer = retail,
            seller = distributor,
            valueInCents = 2000
        )

        val retailDeliveryOrderStateID = createDeliveryOrder(
            purchaseOrderStateID = retailPurchaseOrderStateID,
            product = product,
            buyer = retail,
            seller = distributor,
            deliveryCompany = transporter
        )

        acceptDeliveryOrder(
            deliveryOrderStateID = retailDeliveryOrderStateID,
            buyer = retail,
            seller = distributor,
            deliveryCompany = transporter
        )

        receiveDeliverOrder(
            product = product,
            deliveryOrderStateID = retailDeliveryOrderStateID,
            buyer = retail,
            seller = distributor,
            deliveryCompany = transporter
        )

        //Retail

        sellProduct(
            product = product,
            producer = producer,
            retail = retail
        )

    }

    private fun producerAddProduct(product: Product){
        producer.runFlow(AddProductFlowInitiator(product))

        val actualStockState = getStates<StockState>(producer).single()

        val expectedStockState = StockState(
            owner = producer.party(),
            stock = Stock(
                available = mapOf(Product.Type.A to listOf(product))
            )
        )
        assertEquals(expectedStockState, actualStockState)
    }

    private fun createPurchaseOrder(
        product: Product,
        buyer: StartedMockNode,
        seller: StartedMockNode,
        valueInCents: Int
    ): UniqueIdentifier{

        val productsToBuy = mapOf(Product.Type.A to 1)

        val purchaseOrderStateID = buyer.runFlow(CreatePurchaseOrderFlow.Initiator(
            seller = seller.party(),
            productsToBuy = productsToBuy,
            valueInCents = valueInCents
        ))

        val actualDistributorPurchaseOrderState = getStates<PurchaseOrderState>(buyer).single()
        val actualProducerPurchaseOrderState = getStates<PurchaseOrderState>(seller).single()

        val expectedPurchaseOrderState = PurchaseOrderState(
            buyer = buyer.party(),
            seller = seller.party(),
            products = productsToBuy,
            valueInCents = valueInCents ,
            linearId = actualDistributorPurchaseOrderState.linearId
        )

        assertEquals(expectedPurchaseOrderState, actualDistributorPurchaseOrderState)
        assertEquals(expectedPurchaseOrderState, actualProducerPurchaseOrderState)

        val actualStockState = getStates<StockState>(seller).single()

        val expectedStockState = StockState(
            owner = seller.party(),
            stock = Stock(
                available = mapOf(Product.Type.A to listOf()),
                reserved = mapOf(
                    purchaseOrderStateID to mapOf(Product.Type.A to listOf(product))
                )
            )
        )

        assertEquals(expectedStockState, actualStockState)

        return purchaseOrderStateID
    }

    private fun createDeliveryOrder(
        purchaseOrderStateID: UniqueIdentifier,
        product: Product,
        buyer: StartedMockNode,
        seller: StartedMockNode,
        deliveryCompany: StartedMockNode
        ): UniqueIdentifier{
        val deliveryOrderStateID = seller.runFlow(CreateDeliveryOrderFlow.Initiator(
            deliveryCompany = deliveryCompany.party(),
            purchaseOrderStateID = purchaseOrderStateID
        ))

        //Verify delivery order

        val actualDistributorDeliveryOrderState = getStates<DeliveryOrderState>(buyer).single()
        val actualProducerDeliveryOrderState = getStates<DeliveryOrderState>(seller).single()
        val actualTransporterDeliveryOrderState = getStates<DeliveryOrderState>(deliveryCompany).single()

        val expectedDeliveryOrderState = DeliveryOrderState(
            buyer = buyer.party(),
            seller = seller.party(),
            deliverCompany = deliveryCompany.party(),
            products = mapOf(Product.Type.A to listOf(product)),
            linearId = actualDistributorDeliveryOrderState.linearId
        )

        assertEquals(expectedDeliveryOrderState, actualDistributorDeliveryOrderState)
        assertEquals(expectedDeliveryOrderState, actualProducerDeliveryOrderState)
        assertEquals(expectedDeliveryOrderState, actualTransporterDeliveryOrderState)

        //Verify purchase order

        val actualDistributorPurchaseOrderState = getStates<PurchaseOrderState>(buyer)
        val actualProducerOrderState = getStates<PurchaseOrderState>(seller)

        assertTrue(actualDistributorPurchaseOrderState.isEmpty())
        assertTrue(actualProducerOrderState.isEmpty())

        //Verify stock

        val actualStockState = getStates<StockState>(seller).single()

        val expectedStockState = StockState(
            owner = seller.party(),
            stock = Stock(
                available = mapOf(Product.Type.A to listOf())
            )
        )

        assertEquals(expectedStockState, actualStockState)

        return deliveryOrderStateID
    }

    private fun acceptDeliveryOrder(
        deliveryOrderStateID: UniqueIdentifier,
        buyer: StartedMockNode,
        seller: StartedMockNode,
        deliveryCompany: StartedMockNode
        ){
        val previousDeliveryOrderState = getStates<DeliveryOrderState>(deliveryCompany).single()

        deliveryCompany.runFlow(AcceptDeliveryOrderFlow.Initiator(deliveryOrderStateID))

        val actualDistributorDeliveryOrderState = getStates<DeliveryOrderState>(buyer).single()
        val actualProducerDeliveryOrderState = getStates<DeliveryOrderState>(seller).single()
        val actualTransporterDeliveryOrderState = getStates<DeliveryOrderState>(deliveryCompany).single()

        val expectedDeliveryOrderState = previousDeliveryOrderState.copy(
            accepted = true
        )

        assertEquals(expectedDeliveryOrderState, actualDistributorDeliveryOrderState)
        assertEquals(expectedDeliveryOrderState, actualProducerDeliveryOrderState)
        assertEquals(expectedDeliveryOrderState, actualTransporterDeliveryOrderState)

    }

    private fun receiveDeliverOrder(
        product: Product,
        deliveryOrderStateID: UniqueIdentifier,
        buyer: StartedMockNode,
        seller: StartedMockNode,
        deliveryCompany: StartedMockNode
        ){
        buyer.runFlow(ReceiveDeliveryOrderFlow.Initiator(deliveryOrderStateID))

        //Verify delivery order
        val actualDistributorDeliveryOrderState = getStates<DeliveryOrderState>(buyer)
        val actualProducerDeliveryOrderState = getStates<DeliveryOrderState>(seller)
        val actualTransporterDeliveryOrderState = getStates<DeliveryOrderState>(deliveryCompany)

        assertTrue(actualDistributorDeliveryOrderState.isEmpty())
        assertTrue(actualProducerDeliveryOrderState.isEmpty())
        assertTrue(actualTransporterDeliveryOrderState.isEmpty())

        //Verify stock
        val actualStockState = getStates<StockState>(buyer).single()

        val expectedStockState = StockState(
            owner = buyer.party(),
            stock = Stock(
                available = mapOf(Product.Type.A to listOf(product))
            )
        )

        assertEquals(expectedStockState,actualStockState)



    }

    private fun sellProduct(
        product: Product,
        producer:StartedMockNode,
        retail: StartedMockNode
        ){
        val price = 5000

        retail.runFlow(SellProductFlow.Initiator(
            productType = Product.Type.A,
            price = price
        ))

        //Verify stock

        val actualStockState = getStates<StockState>(retail).single()

        val expectedStockState = StockState(
            owner = retail.party(),
            stock = Stock(
                available = mapOf(Product.Type.A to listOf())
            )
        )

        assertEquals(expectedStockState, actualStockState)

        //Verify sale

        val actualSaleState = getStates<SaleState>(retail).single()

        val expectedSaleState = SaleState(
            retail = retail.party(),
            product = product,
            price = price
        )

        assertEquals(expectedSaleState,actualSaleState)

        //Verify sale notification

        val actualSaleNotificationState = getStates<SaleNotificationState>(producer).single()

        val expectedSaleNotificationState = SaleNotificationState(
            retail = retail.party(),
            product = product
        )

        assertEquals(expectedSaleNotificationState,actualSaleNotificationState)

    }


    private fun StartedMockNode.party(): Party {
        return this.info.singleIdentity()
    }

    private fun <T> StartedMockNode.runFlow(flow: FlowLogic<T>): T {
        val future = this.startFlow(flow)
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    private inline fun <reified T: ContractState> getStates(node: StartedMockNode): List<T>{
        return node
            .services
            .vaultService
            .queryBy<T>()
            .states
            .map {
                it.state.data
            }
    }

}
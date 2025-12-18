package bmps.core.io

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import bmps.core.models._
import java.time.LocalDate
import java.nio.file.Files
import java.io.File

class CSVFileOrderSinkSpec extends AnyFlatSpec with Matchers {

  "CSVFileOrderSink" should "serialize and deserialize orders correctly" in {
    val tempFile = Files.createTempFile("orders", ".csv")
    val sink = new CSVFileOrderSink(tempFile.toString)
    val tradingDay = LocalDate.now()

    val order1 = Order(
      low = 100.0f,
      high = 110.0f,
      timestamp = 1234567890L,
      orderType = OrderType.Long,
      entryType = EntryType.EngulfingOrderBlock,
      contract = "MES",
      status = OrderStatus.Filled,
      profitMultiplier = 2.0f,
      placedTimestamp = Some(1234567900L),
      filledTimestamp = Some(1234567910L),
      closeTimestamp = Some(1234567920L),
      cancelReason = None,
      accountId = Some("ACC123"),
      closedAt = Some(120.0f)
    )

    val order2 = Order(
      low = 200.0f,
      high = 210.0f,
      timestamp = 9876543210L,
      orderType = OrderType.Short,
      entryType = EntryType.Trendy("My Custom Strategy"),
      contract = "ES",
      status = OrderStatus.Planned,
      profitMultiplier = 1.5f
    )

    val order3 = Order(
      low = 300.0f,
      high = 310.0f,
      timestamp = 1111111111L,
      orderType = OrderType.Long,
      entryType = EntryType.Trendy("Strategy, with comma"),
      contract = "MES",
      status = OrderStatus.Cancelled,
      cancelReason = Some("Timeout, force close")
    )

    val state = SystemState(
      tradingDay = tradingDay,
      orders = List(order1, order2, order3)
    )

    // Save orders
    sink.saveOrders(state)

    // Load orders
    val loadedState = sink.loadPastOrders(tradingDay, SystemState(tradingDay = tradingDay))

    loadedState.recentOrders should have size 3

    val loadedOrder1 = loadedState.recentOrders.find(_.timestamp == order1.timestamp).get
    loadedOrder1.entryType shouldBe EntryType.EngulfingOrderBlock
    loadedOrder1.accountId shouldBe Some("ACC123")
    loadedOrder1.closedAt shouldBe Some(120.0f)

    val loadedOrder2 = loadedState.recentOrders.find(_.timestamp == order2.timestamp).get
    loadedOrder2.entryType shouldBe EntryType.Trendy("My Custom Strategy")
    loadedOrder2.contract shouldBe "ES"

    val loadedOrder3 = loadedState.recentOrders.find(_.timestamp == order3.timestamp).get
    // Note: Commas are now preserved via CSV escaping
    loadedOrder3.entryType shouldBe EntryType.Trendy("Strategy, with comma")
    loadedOrder3.cancelReason shouldBe Some("Timeout, force close")

    loadedOrder1 shouldBe order1
    loadedOrder2 shouldBe order2
    loadedOrder3 shouldBe order3

    // Cleanup
    Files.deleteIfExists(tempFile)
  }

  it should "handle missing file gracefully on save and load" in {
    val tempDir = Files.createTempDirectory("bmps_test_missing")
    val filePath = tempDir.resolve("subdir").resolve("missing_orders.csv") // Test subdirectory creation too
    val sink = new CSVFileOrderSink(filePath.toString)
    val tradingDay = LocalDate.now()
    
    // Test Load on missing file
    val loadedState = sink.loadPastOrders(tradingDay, SystemState(tradingDay = tradingDay))
    loadedState.recentOrders shouldBe empty

    // Test Save on missing file (should create file and dirs)
    val order = Order(
      low = 100.0f, high = 110.0f, timestamp = 1L, orderType = OrderType.Long, 
      entryType = EntryType.EngulfingOrderBlock, contract = "MES"
    )
    val state = SystemState(tradingDay = tradingDay, orders = List(order))
    
    sink.saveOrders(state)
    
    Files.exists(filePath) shouldBe true
    
    // Verify content
    val loadedState2 = sink.loadPastOrders(tradingDay, SystemState(tradingDay = tradingDay))
    loadedState2.recentOrders should have size 1
    
    // Cleanup
    Files.deleteIfExists(filePath)
    Files.deleteIfExists(tempDir.resolve("subdir"))
    Files.deleteIfExists(tempDir)
  }
}

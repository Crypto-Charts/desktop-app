package it.menzani.cryptocharts

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.treeToValue
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Application
import javafx.application.Platform
import javafx.beans.value.ChangeListener
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.scene.layout.Pane
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.Text
import javafx.stage.Stage
import javafx.util.Duration
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Network
import org.stellar.sdk.Server
import org.stellar.sdk.responses.AccountResponse
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URL
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.*
import java.util.concurrent.*

class App : Application() {
    private lateinit var mapper: ObjectMapper
    private lateinit var executor: ExecutorService
    private var setup: Setup? = null
    private var suppressed: Exception? = null
    private lateinit var fetcher: Future<Fetcher.Result>

    override fun init() {
        mapper = jacksonObjectMapper()
        executor = Executors.newSingleThreadExecutor()
        try {
            setup = loadSetup()
        } catch (e: IOException) {
            suppressed = e
        }
        refresh()
        playSong()
    }

    private fun playSong() {
        val song = this.javaClass.getResource("song.mp3") ?: return
        val media = Media(song.toExternalForm())
        media.setOnError { media.error.printStackTrace() }
        val mediaPlayer = MediaPlayer(media)
        mediaPlayer.setOnError { mediaPlayer.error.printStackTrace() }
        mediaPlayer.play()
    }

    private fun loadSetup(): Setup {
        val external = File(parameters.named.getOrDefault("setup-file", "setup.json"))
        val internal = this.javaClass.getResource(external.name)

        if (internal == null) {
            return mapper.readValue(external)
        }
        return mapper.readValue(internal)
    }

    @Synchronized
    private fun refresh() {
        fetcher = executor.submit(Fetcher(suppressed, setup, mapper))
    }

    override fun start(primaryStage: Stage) {
        primaryStage.icons.add(Image(this.javaClass.getResourceAsStream("icon.png")))
        primaryStage.title = "Crypto Charts"

        schedule(Duration.minutes(10.0)) {
            if (primaryStage.scene == null) {
                primaryStage.scene = Scene(createPane())
                primaryStage.show()
            } else {
                refresh()
                primaryStage.scene.root = createPane()
            }
            primaryStage.sizeToScene()
        }
        primaryStage.iconifiedProperty().addListener(ChangeListener { _, _, newValue ->
            if (newValue) return@ChangeListener
            primaryStage.scene.root = createPane()
        })
    }

    private fun createPane(): Pane {
        val pane = StackPane(createText())
        pane.padding = Insets(10.0)
        return pane
    }

    private fun createText(): Text {
        val fetcherResult: Fetcher.Result
        try {
            fetcherResult = synchronized(this) {
                return@synchronized fetcher.get()
            }
        } catch (e: ExecutionException) {
            e.printStackTrace()
            val text = Text(e.printStackTraceString())
            text.fill = Color.RED
            return text
        }

        val textContent = StringBuilder()
        var totalNetWorth = 0.0
        val localCurrencyFormatter = fetcherResult.localCurrency.formatter
        for (currency in fetcherResult.currencies) {
            textContent.append(currency.toString(localCurrencyFormatter))
            textContent.append(System.lineSeparator())
            totalNetWorth += currency.netWorth
        }
        textContent.append("Total Net Worth: ")
        textContent.append(localCurrencyFormatter.format(totalNetWorth))

        val text = Text(textContent.toString())
        text.font = Font.loadFont(this.javaClass.getResourceAsStream("SourceSansPro/SourceSansPro-Light.otf"), 16.0)
        return text
    }

    private fun schedule(interval: Duration, action: () -> Unit) {
        Platform.runLater(action)
        val animation = Timeline(KeyFrame(interval, EventHandler { action() }))
        animation.cycleCount = Timeline.INDEFINITE
        animation.play()
    }

    override fun stop() {
        executor.shutdown()
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            Application.launch(App::class.java, *args)
        }
    }
}

private fun ExecutionException.printStackTraceString(): String {
    val writer = StringWriter()
    writer.use { this.cause!!.printStackTrace(PrintWriter(it)) }
    return writer.toString()
}

class Fetcher(private val suppressed: Exception?, private val setup: Setup?, private val mapper: ObjectMapper) : Callable<Fetcher.Result> {
    override fun call(): Result {
        if (suppressed != null) throw suppressed

        val currencies: MutableList<Currency> = mutableListOf()
        for (currencyOwned in setup!!.currenciesOwned) {
            val tree = fetch(currencyOwned.id, setup.localCurrency.id.toUpperCase())
            val currency: Currency = mapper.treeToValue(tree)
            val amount = if (currencyOwned.id == "stellar" && currencyOwned.stellarAccountId != null) {
                Arrays.stream(currencyOwned.stellarAccount().balances)
                        .filter { it.assetType == "native" }
                        .findFirst().get()
                        .balance.toDouble()
            } else {
                currencyOwned.amount
            }
            val price = tree["price_${setup.localCurrency.id.toLowerCase()}"].asDouble()
            currency.netWorth = amount * price
            currencies.add(currency)
        }
        return Result(currencies, setup.localCurrency)
    }

    private fun fetch(currencyId: String, localCurrencyId: String): JsonNode {
        val response: JsonNode = mapper.readTree(URL(
                "https://api.coinmarketcap.com/v1/ticker/$currencyId/?convert=$localCurrencyId"))
        if (response.isArray && response.size() == 1) {
            return response[0]
        }
        throw Exception("Unexpected response: $response")
    }

    class Result(val currencies: List<Currency>, val localCurrency: LocalCurrency)
}

data class Setup(
        val localCurrency: LocalCurrency,
        val currenciesOwned: Array<CurrencyOwned>
)

data class LocalCurrency(
        val id: String,
        val languageTag: String
) {
    val formatter = CurrencyFormatter(Locale.forLanguageTag(languageTag))
}

data class CurrencyOwned(
        val id: String,
        val amount: Double,
        val stellarAccountId: String?
) {
    private val horizon by lazy {
        Network.usePublicNetwork()
        Server("https://horizon.stellar.org")
    }

    fun stellarAccount(): AccountResponse = horizon.accounts().account(KeyPair.fromAccountId(stellarAccountId!!))
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Currency(
        val symbol: String,
        val price_usd: String
) {
    private val priceFormatter = CurrencyFormatter(Locale.US)
    var netWorth = 0.0

    fun toString(formatter: CurrencyFormatter): String {
        val price_usd = priceFormatter.format(price_usd.toDouble())
        val netWorth = formatter.format(netWorth)
        return "$symbol/USD: $price_usd — $symbol Net Worth: $netWorth"
    }
}

class CurrencyFormatter(locale: Locale) {
    private val formatter: NumberFormat = DecimalFormat.getCurrencyInstance(locale)

    fun format(price: Double): String {
        val digits = when {
            price < 0.0001 -> 6
            price < 0.001 -> 5
            price < 0.01 -> 4
            price < 0.1 -> 3
            price < 10 -> 2
            price < 100 -> 1
            else -> 0
        }
        formatter.minimumFractionDigits = digits
        formatter.maximumFractionDigits = digits
        return formatter.format(price)
    }
}
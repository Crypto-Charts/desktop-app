package it.menzani.cryptocharts

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Application
import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.beans.binding.DoubleBinding
import javafx.beans.property.DoubleProperty
import javafx.beans.property.SimpleDoubleProperty
import javafx.beans.value.ChangeListener
import javafx.beans.value.ObservableValue
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.layout.Pane
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.Text
import javafx.stage.Stage
import javafx.util.Duration
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class App : Application() {
    private lateinit var mapper: ObjectMapper
    private lateinit var executor: ExecutorService
    private var setup: Setup? = null
    private var suppressed: Exception? = null
    private lateinit var fetcher: Future<Fetcher.Result>
    private lateinit var mediaPlayer: MediaPlayer // Do not inline: https://stackoverflow.com/a/47837424/3453226
    private lateinit var presentation: Presentation

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

    private fun loadSetup(): Setup {
        val external = File(parameters.named.getOrDefault("setup-file", "setup.json"))
        val internal = javaClass.getResource(external.name)
        return if (internal == null) mapper.readValue(external) else mapper.readValue(internal)
    }

    @Synchronized
    private fun refresh() {
        fetcher = executor.submit(Fetcher(suppressed, setup, mapper))
    }

    private fun playSong() {
        val song = javaClass.getResource("song.mp3") ?: return
        val media = Media(song.toExternalForm())
        media.setOnError { media.error.printStackTrace() }
        mediaPlayer = MediaPlayer(media)
        mediaPlayer.setOnError { mediaPlayer.error.printStackTrace() }
        mediaPlayer.play()
    }

    override fun start(primaryStage: Stage) {
        javaClass.getResourceAsStream("icon.png").use { primaryStage.icons.add(Image(it)) }
        primaryStage.title = "Crypto Charts"
        primaryStage.isResizable = false
        primaryStage.fullScreenExitHint = ""
        presentation = Presentation(primaryStage)

        Duration.minutes(10.0).scheduleTimer {
            if (primaryStage.scene == null) {
                primaryStage.scene = Scene(createPane())
                primaryStage.show()
                presentation.registerEvents()
            } else {
                refresh()
                primaryStage.scene.root = createPane()
            }
            primaryStage.sizeToScene()
        }
        primaryStage.iconifiedProperty().addListener { _, oldValue, _ ->
            if (oldValue) primaryStage.scene.root = createPane()
        }
    }

    private fun createPane(): Pane {
        val pane = StackPane(createText())
        pane.padding = Insets(10.0)
        return pane
    }

    private fun createText(): Text {
        val fetcherResult = try {
            synchronized(this) { fetcher.get() }
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
        javaClass.getResourceAsStream("SourceSansPro/SourceSansPro-Light.otf").use { text.font = Font.loadFont(it, presentation.defaultFontSize) }
        text.styleProperty().bind(Bindings.concat("-fx-font-size: ", presentation.fontSizeProperty.asString(), "px;")) // https://stackoverflow.com/a/23832850/3453226
        return text
    }

    override fun stop() {
        executor.shutdown()
    }

    private class Presentation(private val stage: Stage) : ChangeListener<Boolean>, EventHandler<KeyEvent> {
        private lateinit var scalingFontSize: DoubleBinding
        private val shortcut: KeyCombination = KeyCodeCombination(KeyCode.F11)
        val defaultFontSize = 16.0
        val fontSizeProperty: DoubleProperty = SimpleDoubleProperty(defaultFontSize)

        fun registerEvents() {
            val scene = stage.scene
            scalingFontSize = scene.widthProperty().add(scene.heightProperty()).divide(30)
            stage.fullScreenProperty().addListener(this)
            scene.addEventFilter(KeyEvent.KEY_PRESSED, this)
        }

        override fun changed(observable: ObservableValue<out Boolean>, oldValue: Boolean, newValue: Boolean) {
            if (newValue) {
                fontSizeProperty.bind(scalingFontSize)
            } else {
                fontSizeProperty.unbind()
                fontSizeProperty.set(defaultFontSize)
            }
        }

        override fun handle(event: KeyEvent) {
            if (!shortcut.match(event)) return
            event.consume()
            stage.isFullScreen = true
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            Application.launch(App::class.java, *args)
        }
    }
}

private fun Duration.scheduleTimer(task: () -> Unit) {
    Platform.runLater(task)
    val animation = Timeline(KeyFrame(this, EventHandler { task() }))
    animation.cycleCount = Timeline.INDEFINITE
    animation.play()
}

private fun ExecutionException.printStackTraceString(): String {
    val writer = StringWriter()
    writer.use { this.cause!!.printStackTrace(PrintWriter(it)) }
    return writer.toString()
}
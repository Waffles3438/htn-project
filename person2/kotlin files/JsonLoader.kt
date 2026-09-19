import kotlinx.serialization.json.Json
import java.io.File

object JsonLoader {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun loadBreadboard(path: String): Breadboard {
        val text = File(path).readText()
        return json.decodeFromString<Breadboard>(text)
    }

    fun loadComponents(path: String): ComponentList {
        val text = File(path).readText()
        return json.decodeFromString<ComponentList>(text)
    }

    fun loadAssets(path: String): AssetList {
        val text = File(path).readText()
        return json.decodeFromString<AssetList>(text)
    }

    fun loadPlacement(path: String): Placement {
        val text = File(path).readText()
        return json.decodeFromString<Placement>(text)
    }
}
import kotlinx.serialization.Serializable

@Serializable
data class AssetList(
    val assets: List<Asset>
)

@Serializable
data class Asset(
    val assetId: String,
    val file: String,
    val scale: Vector3,
    val rotation: Vector3,
    val terminals: Map<String, String>
)
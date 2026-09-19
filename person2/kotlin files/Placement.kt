import kotlinx.serialization.Serializable

@Serializable
data class Placement(
    val version: Int,
    val breadboardModel: String,
    val components: List<PlacedComponent>,
    val jumperWires: List<JumperWire>
)

@Serializable
data class PlacedComponent(
    val id: String,
    val type: String,
    val value: String? = null,
    val assetId: String,
    val terminals: List<Terminal>,
    val buildStep: Int
)

@Serializable
data class Terminal(
    val id: String,
    val holeId: String
)

@Serializable
data class JumperWire(
    val id: String,
    val fromHole: String,
    val toHole: String,
    val color: String,
    val buildStep: Int
)
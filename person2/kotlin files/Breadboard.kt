import kotlinx.serialization.Serializable

@Serializable
data class Breadboard(
    val id: String,
    val units: String,
    val dimensions: Dimensions,
    val origin: Vector3,
    val axes: Axes,
    val pitch: Double,
    val holeCount: Int,
    val holes: List<Hole>
)

@Serializable
data class Dimensions(
    val width: Double,
    val height: Double,
    val depth: Double
)

@Serializable
data class Vector3(
    val x: Double,
    val y: Double,
    val z: Double
)

@Serializable
data class Axes(
    val x: String,
    val y: String,
    val z: String
)

@Serializable
data class Hole(
    val id: String,
    val x: Double,
    val y: Double,
    val z: Double
)
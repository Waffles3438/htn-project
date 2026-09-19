import kotlinx.serialization.Serializable

@Serializable
data class ComponentList(
    val components: List<Component>
)

@Serializable
data class Component(
    val type: String,
    val value: String? = null,
    val quantity: Int
)
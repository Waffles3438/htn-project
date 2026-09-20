package com.htn.breadboardar.render

import com.htn.breadboardar.circuit.BoardGeometry
import com.htn.breadboardar.circuit.BoardPoint
import com.htn.breadboardar.circuit.CircuitDefinition
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Builds one self-contained glTF scene from validated placement JSON and exported Unity meshes.
 * Native scene axes: X along numbered rows, Y above the board, Z across lettered columns.
 * Board units remain meters; external devices never cause the whole scene to be rescaled.
 */
internal class CircuitGlbBuilder(
    private val board: BoardGeometry,
    private val breadboardGlb: ByteArray,
    private val componentJson: String,
) {
    fun build(circuit: CircuitDefinition): ByteBuffer {
        board.validate(circuit)
        val input = ByteBuffer.wrap(breadboardGlb).order(ByteOrder.LITTLE_ENDIAN)
        require(input.int == 0x46546c67 && input.int == 2) { "Invalid bundled board GLB" }
        require(input.int == breadboardGlb.size)
        val jsonLength = input.int
        require(input.int == 0x4e4f534a)
        val jsonBytes = ByteArray(jsonLength).also { input.get(it) }
        val doc = JSONObject(String(jsonBytes, Charsets.UTF_8).trim())
        val binLength = input.int
        require(input.int == 0x004e4942)
        val bin = ByteArrayOutputStream().apply { write(ByteArray(binLength).also { input.get(it) }) }
        val nodes = doc.getJSONArray("nodes")
        val meshes = doc.getJSONArray("meshes")
        val views = doc.getJSONArray("bufferViews")
        val accessors = doc.getJSONArray("accessors")
        val materials = doc.getJSONArray("materials")
        val sceneNodes = JSONArray()
        doc.put("scenes", JSONArray().put(JSONObject().put("nodes", sceneNodes))).put("scene", 0)
        doc.getJSONObject("asset").put("generator", "Circuit Android / Unity prefab export")
        doc.put("extras", JSONObject().put("sessionId", circuit.sessionId).put("holeMapVersion", circuit.holeMapVersion))

        fun array(values: FloatArray) = JSONArray(values.toList())
        fun floats(values: FloatArray, type: String, dimension: Int, bounds: Boolean = false): Int {
            val data = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { data.putFloat(it) }
            val view = views.length()
            views.put(JSONObject().put("buffer", 0).put("byteOffset", bin.size()).put("byteLength", data.capacity()).put("target", 34962))
            bin.write(data.array())
            val accessor = JSONObject().put("bufferView", view).put("componentType", 5126).put("count", values.size / dimension).put("type", type)
            if (bounds) {
                accessor.put("min", JSONArray((0 until dimension).map { axis -> values.indices.filter { it % dimension == axis }.minOf { values[it] } }))
                accessor.put("max", JSONArray((0 until dimension).map { axis -> values.indices.filter { it % dimension == axis }.maxOf { values[it] } }))
            }
            val index = accessors.length(); accessors.put(accessor); return index
        }
        fun indices(values: IntArray): Int {
            val data = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { data.putInt(it) }
            val view = views.length()
            views.put(JSONObject().put("buffer", 0).put("byteOffset", bin.size()).put("byteLength", data.capacity()).put("target", 34963))
            bin.write(data.array())
            val index = accessors.length()
            accessors.put(JSONObject().put("bufferView", view).put("componentType", 5125).put("count", values.size).put("type", "SCALAR"))
            return index
        }
        fun material(name: String, color: FloatArray, metallic: Float = 0f): Int {
            val index = materials.length()
            materials.put(JSONObject().put("name", name).put("doubleSided", true).put("pbrMetallicRoughness", JSONObject()
                .put("baseColorFactor", array(color)).put("roughnessFactor", .55).put("metallicFactor", metallic)))
            return index
        }
        fun primitive(p: FloatArray, n: FloatArray, ix: IntArray, mat: Int): JSONObject = JSONObject()
            .put("attributes", JSONObject().put("POSITION", floats(p, "VEC3", 3, true)).put("NORMAL", floats(n, "VEC3", 3)))
            .put("indices", indices(ix)).put("material", mat)
        fun mesh(name: String, primitives: JSONArray): Int {
            val index = meshes.length(); meshes.put(JSONObject().put("name", name).put("primitives", primitives)); return index
        }
        fun node(name: String, mesh: Int, at: FloatArray, scale: FloatArray = floatArrayOf(1f,1f,1f), rotation: FloatArray? = null) {
            val n = JSONObject().put("name", name).put("mesh", mesh).put("translation", array(at)).put("scale", array(scale))
            if (rotation != null) n.put("rotation", array(rotation))
            sceneNodes.put(nodes.length()); nodes.put(n)
        }

        // Retain the detailed native breadboard model, excluding its coincident opaque LP proxy.
        val hpIndex = (0 until nodes.length()).first { nodes.getJSONObject(it).optString("name") == "HP" }
        val hp = nodes.getJSONObject(hpIndex)
        val matrix = hp.getJSONArray("matrix")
        val primitives = meshes.getJSONObject(hp.getInt("mesh")).getJSONArray("primitives")
        val lo = FloatArray(3) { Float.POSITIVE_INFINITY }; val hi = FloatArray(3) { Float.NEGATIVE_INFINITY }
        for (i in 0 until primitives.length()) {
            val a = accessors.getJSONObject(primitives.getJSONObject(i).getJSONObject("attributes").getInt("POSITION"))
            for (corner in 0..7) {
                val p = FloatArray(3) { axis -> a.getJSONArray(if (corner and (1 shl axis) == 0) "min" else "max").getDouble(axis).toFloat() }
                for (axis in 0..2) {
                    val value = (0..2).sumOf { matrix.getDouble(it*4+axis)*p[it] } + matrix.getDouble(12+axis)
                    lo[axis] = min(lo[axis], value.toFloat()); hi[axis] = max(hi[axis], value.toFloat())
                }
            }
        }
        val boardScale = .165f / (hi[0] - lo[0])
        for (i in 0 until nodes.length()) nodes.getJSONObject(i).remove("children")
        val parent = JSONObject().put("name", "Breadboard").put("children", JSONArray().put(hpIndex))
            .put("scale", array(floatArrayOf(boardScale,boardScale,boardScale)))
            .put("translation", array(floatArrayOf(-(hi[0]+lo[0])*.5f*boardScale, -hi[1]*boardScale, -(hi[2]+lo[2])*.5f*boardScale)))
        sceneNodes.put(nodes.length()); nodes.put(parent)

        val models = JSONObject(componentJson).getJSONObject("models")
        val modelMeshes = mutableMapOf<String,Int>()
        for (type in circuit.components.map { it.type }.filter { it != "power_supply" }.distinct()) {
            val model = models.getJSONObject(type)
            val parts = model.getJSONArray("primitives")
            val result = JSONArray()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                fun values(key: String): FloatArray { val a = part.getJSONArray(key); return FloatArray(a.length()) { a.getDouble(it).toFloat() } }
                val ix = part.getJSONArray("indices")
                result.put(primitive(values("positions"), values("normals"), IntArray(ix.length()) { ix.getInt(it) },
                    material(part.getString("name"), values("color"), part.optDouble("metallic",0.0).toFloat())))
            }
            modelMeshes[type] = mesh("Unity $type", result)
        }
        fun native(p: BoardPoint) = floatArrayOf(p.z - .07874f, p.y, p.x - .01397f)
        val colors = mapOf("red" to floatArrayOf(.82f,.07f,.04f,1f), "black" to floatArrayOf(.025f,.03f,.025f,1f),
            "yellow" to floatArrayOf(.95f,.64f,.04f,1f), "blue" to floatArrayOf(.04f,.2f,.8f,1f),
            "green" to floatArrayOf(.05f,.5f,.2f,1f), "silver" to floatArrayOf(.6f,.65f,.66f,1f), "teal" to floatArrayOf(.02f,.3f,.3f,1f))
        val tubeMeshes = mutableMapOf<String,Int>()
        fun tube(color: String): Int = tubeMeshes.getOrPut(color) {
            val p = mutableListOf<Float>(); val n = mutableListOf<Float>(); val ix = mutableListOf<Int>()
            for (i in 0..8) {
                val angle = i*2*PI/8; val x = cos(angle).toFloat(); val z = sin(angle).toFloat()
                for (y in listOf(0f,1f)) { p.addAll(listOf(x,y,z)); n.addAll(listOf(x,0f,z)) }
                if (i < 8) { val j=i*2; ix.addAll(listOf(j,j+1,j+2,j+2,j+1,j+3)) }
            }
            mesh("Wire $color", JSONArray().put(primitive(p.toFloatArray(),n.toFloatArray(),ix.toIntArray(),material(color,colors[color] ?: colors.getValue("black")))))
        }
        fun segment(name: String, a: FloatArray, b: FloatArray, color: String, radius: Float) {
            val d = FloatArray(3) { b[it]-a[it] }; val length=sqrt(d.sumOf { (it*it).toDouble() }).toFloat()
            if (length < .000001f) return
            // Quaternion rotating unit +Y onto the wire segment.
            val rotation = if (d[1]/length < -.9999f) floatArrayOf(1f,0f,0f,0f) else {
                val q=floatArrayOf(d[2]/length,0f,-d[0]/length,1f+d[1]/length)
                val norm=sqrt(q.sumOf { (it*it).toDouble() }).toFloat(); FloatArray(4) { q[it]/norm }
            }
            node(name,tube(color),a,floatArrayOf(radius,length,radius),rotation)
        }
        circuit.components.forEach { c ->
            val points = c.terminals.values.map { native(requireNotNull(board.hole(it))) }
            val center = FloatArray(3) { axis -> points.map { it[axis] }.average().toFloat() }
            if (c.type != "power_supply") {
                node(c.id,modelMeshes.getValue(c.type),floatArrayOf(center[0],.001f,center[2]))
            }
            points.forEachIndexed { i,p ->
                val end = if (c.type == "power_supply") floatArrayOf(p[0],.007f,p[2]) else floatArrayOf(center[0],.004f,center[2])
                segment("${c.id}:lead:$i",p,end,if(c.type=="power_supply") if(i==0) "red" else "black" else "silver",.00025f)
            }
        }
        circuit.jumperWires.forEach { wire ->
            val a=native(requireNotNull(board.endpoint(wire.from,circuit))); val b=native(requireNotNull(board.endpoint(wire.to,circuit)))
            fun point(t:Float)=FloatArray(3) { a[it]+(b[it]-a[it])*t + if(it==1) .009f*4*t*(1-t)+.0005f else 0f }
            for (i in 0 until 20) segment("${wire.id}:$i",point(i/20f),point((i+1)/20f),wire.color,.00045f)
        }
        // Explicit schematic Uno proxy: no Uno mesh was supplied by the team.
        circuit.externalDevices.forEach { device ->
            val p = floatArrayOf(-1f,-1f,-1f, 1f,-1f,-1f, 1f,1f,-1f, -1f,1f,-1f, -1f,-1f,1f, 1f,-1f,1f, 1f,1f,1f, -1f,1f,1f)
            val n = FloatArray(p.size) { p[it]/sqrt(3f) }
            val ix=intArrayOf(0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,3,7,6,3,6,2,0,4,7,0,7,3,1,2,6,1,6,5)
            val m=mesh("Uno schematic proxy",JSONArray().put(primitive(p,n,ix,material("Uno",colors.getValue("teal")))))
            node(device.id,m,native(BoardPoint(-.0525f,.001f,.0385f)),floatArrayOf(.0245f,.0015f,.0295f))
        }
        doc.put("buffers",JSONArray().put(JSONObject().put("byteLength",bin.size())))
        val json=doc.toString().toByteArray(Charsets.UTF_8); val padded=(json.size+3)/4*4
        return ByteBuffer.allocateDirect(12+8+padded+8+bin.size()).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546c67);putInt(2);putInt(capacity());putInt(padded);putInt(0x4e4f534a);put(json)
            repeat(padded-json.size) { put(32.toByte()) };putInt(bin.size());putInt(0x004e4942);put(bin.toByteArray());rewind()
        }
    }
}

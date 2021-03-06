/*
 * Copyright 2020 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/mamoe/mirai/blob/master/LICENSE
 */

package net.mamoe.mirai.plugin

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.alibaba.fastjson.TypeReference
import com.alibaba.fastjson.parser.Feature
import kotlinx.serialization.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.full.isSubclassOf


/**
 * TODO: support all config types
 * only JSON is now supported
 *
 */

interface Config {
    fun getConfigSection(key: String): ConfigSection
    fun getString(key: String): String
    fun getInt(key: String): Int
    fun getFloat(key: String): Float
    fun getDouble(key: String): Double
    fun getLong(key: String): Long
    fun getBoolean(key: String): Boolean
    fun getList(key: String): List<*>
    fun getStringList(key: String): List<String>
    fun getIntList(key: String): List<Int>
    fun getFloatList(key: String): List<Float>
    fun getDoubleList(key: String): List<Double>
    fun getLongList(key: String): List<Long>
    operator fun set(key: String, value: Any)
    operator fun get(key: String): Any?
    fun exist(key: String): Boolean
    fun setIfAbsent(key: String, value: Any)
    fun asMap(): Map<String, Any>
    fun save()

    companion object {
        fun load(fileName: String): Config {
            return load(File(fileName.replace("//", "/")))
        }

        fun load(file: File): Config {
            if (!file.exists()) {
                file.createNewFile()
            }
            return when (file.extension.toLowerCase()) {
                "json" -> JsonConfig(file)
                "yml" -> YamlConfig(file)
                "yaml" -> YamlConfig(file)
                "mirai" -> YamlConfig(file)
                "ini" -> IniConfig(file)
                "toml" -> IniConfig(file)
                "properties" -> IniConfig(file)
                "property" -> IniConfig(file)
                "data" -> IniConfig(file)
                else -> error("Unsupported file config type ${file.extension.toLowerCase()}")
            }
        }
    }
}


fun File.loadAsConfig(): Config {
    return Config.load(this)
}

/* 最简单的代理 */
inline operator fun <reified T : Any> Config.getValue(thisRef: Any?, property: KProperty<*>): T {
    return smartCast(property)
}

inline operator fun <reified T : Any> Config.setValue(thisRef: Any?, property: KProperty<*>, value: T) {
    this[property.name] = value
}

/* 带有默认值的代理 */
inline fun <reified T : Any> Config.withDefault(
    noinline defaultValue: () -> T
): ReadWriteProperty<Any, T> {
    val default by lazy { defaultValue.invoke() }
    return object : ReadWriteProperty<Any, T> {
        override fun getValue(thisRef: Any, property: KProperty<*>): T {
            if (this@withDefault.exist(property.name)) {//unsafe
                return this@withDefault.smartCast(property)
            }
            return default
        }

        override fun setValue(thisRef: Any, property: KProperty<*>, value: T) {
            this@withDefault[property.name] = value
        }
    }
}

/* 带有默认值且如果为空会写入的代理 */
inline fun <reified T : Any> Config.withDefaultWrite(
    noinline defaultValue: () -> T
): WithDefaultWriteLoader<T> {
    return WithDefaultWriteLoader(T::class, this, defaultValue, false)
}

/* 带有默认值且如果为空会写入保存的代理 */
inline fun <reified T : Any> Config.withDefaultWriteSave(
    noinline defaultValue: () -> T
): WithDefaultWriteLoader<T> {
    return WithDefaultWriteLoader(T::class, this, defaultValue, true)
}

class WithDefaultWriteLoader<T : Any>(
    private val _class: KClass<T>,
    private val config: Config,
    private val defaultValue: () -> T,
    private val save: Boolean
) {
    operator fun provideDelegate(
        thisRef: Any,
        prop: KProperty<*>
    ): ReadWriteProperty<Any, T> {
        val defaultValue by lazy { defaultValue.invoke() }
        config.setIfAbsent(prop.name, defaultValue)
        if (save) {
            config.save()
        }
        return object : ReadWriteProperty<Any, T> {
            override fun getValue(thisRef: Any, property: KProperty<*>): T {
                if (config.exist(property.name)) {//unsafe
                    return config._smartCast(property.name, _class)
                }
                return defaultValue
            }

            override fun setValue(thisRef: Any, property: KProperty<*>, value: T) {
                config[property.name] = value
            }
        }
    }
}

inline fun <reified T : Any> Config.smartCast(property: KProperty<*>): T {
    return _smartCast(property.name, T::class)
}

@Suppress("IMPLICIT_CAST_TO_ANY", "UNCHECKED_CAST")
fun <T : Any> Config._smartCast(propertyName: String, _class: KClass<T>): T {
    return when (_class) {
        String::class -> this.getString(propertyName)
        Int::class -> this.getInt(propertyName)
        Float::class -> this.getFloat(propertyName)
        Double::class -> this.getDouble(propertyName)
        Long::class -> this.getLong(propertyName)
        Boolean::class -> this.getBoolean(propertyName)
        else -> when {
            _class.isSubclassOf(ConfigSection::class) -> this.getConfigSection(propertyName)
            _class == List::class || _class == MutableList::class -> {
                val list = this.getList(propertyName)
                return if (list.isEmpty()) {
                    list
                } else {
                    when (list[0]!!::class) {
                        String::class -> getStringList(propertyName)
                        Int::class -> getIntList(propertyName)
                        Float::class -> getFloatList(propertyName)
                        Double::class -> getDoubleList(propertyName)
                        Long::class -> getLongList(propertyName)
                        else -> {
                            error("unsupported type")
                        }
                    }
                } as T
            }
            else -> {
                error("unsupported type")
            }
        }
    } as T
}



interface ConfigSection : Config {
    override fun getConfigSection(key: String): ConfigSection {
        return (get(key) ?: error("ConfigSection does not contain $key ")) as ConfigSection
    }

    override fun getString(key: String): String {
        return (get(key) ?: error("ConfigSection does not contain $key ")).toString()
    }

    override fun getInt(key: String): Int {
        return (get(key) ?: error("ConfigSection does not contain $key ")).toString().toInt()
    }

    override fun getFloat(key: String): Float {
        return (get(key) ?: error("ConfigSection does not contain $key ")).toString().toFloat()
    }

    override fun getBoolean(key: String): Boolean {
        return (get(key) ?: error("ConfigSection does not contain $key ")).toString().toBoolean()
    }

    override fun getDouble(key: String): Double {
        return (get(key) ?: error("ConfigSection does not contain $key ")).toString().toDouble()
    }

    override fun getLong(key: String): Long {
        return (get(key) ?: error("ConfigSection does not contain $key ")).toString().toLong()
    }

    override fun getList(key: String): List<*> {
        return ((get(key) ?: error("ConfigSection does not contain $key ")) as List<*>)
    }

    override fun getStringList(key: String): List<String> {
        return ((get(key) ?: error("ConfigSection does not contain $key ")) as List<*>).map { it.toString() }
    }

    override fun getIntList(key: String): List<Int> {
        return ((get(key) ?: error("ConfigSection does not contain $key ")) as List<*>).map { it.toString().toInt() }
    }

    override fun getFloatList(key: String): List<Float> {
        return ((get(key) ?: error("ConfigSection does not contain $key ")) as List<*>).map { it.toString().toFloat() }
    }

    override fun getDoubleList(key: String): List<Double> {
        return ((get(key) ?: error("ConfigSection does not contain $key ")) as List<*>).map { it.toString().toDouble() }
    }

    override fun getLongList(key: String): List<Long> {
        return ((get(key) ?: error("ConfigSection does not contain $key ")) as List<*>).map { it.toString().toLong() }
    }

    override fun exist(key: String): Boolean {
        return get(key) != null
    }

    override fun setIfAbsent(key: String, value: Any) {
        if (!exist(key)) set(key, value)
    }
}

@Serializable
open class ConfigSectionImpl() : ConcurrentHashMap<String, Any>(), ConfigSection {
    override fun set(key: String, value: Any) {
        super.put(key, value)
    }

    override operator fun get(key: String): Any? {
        return super.get(key)
    }

    override fun exist(key: String): Boolean {
        return containsKey(key)
    }

    override fun asMap(): Map<String, Any> {
        return this
    }

    override fun save() {

    }

    override fun setIfAbsent(key: String, value: Any) {
        this.putIfAbsent(key, value)//atomic
    }
}


interface FileConfig : Config {
    fun deserialize(content: String): ConfigSection

    fun serialize(config: ConfigSection): String
}


abstract class FileConfigImpl internal constructor(
    private val file: File
) : FileConfig, ConfigSection {

    private val content by lazy {
        deserialize(file.readText())
    }

    override fun save() {
        if (!file.exists()) {
            file.createNewFile()
        }
        file.writeText(serialize(content))
    }

    override fun get(key: String): Any? {
        return content[key]
    }

    override fun set(key: String, value: Any) {
        content[key] = value
    }

    override fun asMap(): Map<String, Any> {
        return content.asMap()
    }

}

class JsonConfig internal constructor(file: File) : FileConfigImpl(file) {
    @UnstableDefault
    override fun deserialize(content: String): ConfigSection {
        if (content.isEmpty() || content.isBlank() || content == "{}") {
            return ConfigSectionImpl()
        }
        return JSON.parseObject<ConfigSectionImpl>(
            content,
            object : TypeReference<ConfigSectionImpl>() {},
            Feature.OrderedField
        )
    }

    @UnstableDefault
    override fun serialize(config: ConfigSection): String {
        return JSONObject.toJSONString(config)
    }
}

class YamlConfig internal constructor(file: File) : FileConfigImpl(file) {
    override fun deserialize(content: String): ConfigSection {
        TODO("崔崔还没有写") //To change body of created functions use File | Settings | File Templates.
    }

    override fun serialize(config: ConfigSection): String {
        TODO("崔崔还没有写") //To change body of created functions use File | Settings | File Templates.
    }

}

class IniConfig internal constructor(file: File) : FileConfigImpl(file) {
    override fun deserialize(content: String): ConfigSection {
        TODO("崔崔还没有写") //To change body of created functions use File | Settings | File Templates.
    }

    override fun serialize(config: ConfigSection): String {
        TODO("崔崔还没有写") //To change body of created functions use File | Settings | File Templates.
    }

}

package korlibs.korge.gradle

import korlibs.korge.gradle.targets.*
import korlibs.korge.gradle.targets.android.*
import korlibs.korge.gradle.targets.desktop.*
import korlibs.korge.gradle.targets.ios.*
import korlibs.korge.gradle.targets.js.*
import korlibs.korge.gradle.targets.jvm.*
import korlibs.korge.gradle.util.*
import org.gradle.api.*
import java.io.*
import groovy.text.*
import korlibs.korge.gradle.processor.*
import org.gradle.api.artifacts.*
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import java.net.*
import java.time.*
import java.util.*
import javax.inject.Inject
import javax.naming.*
import kotlin.collections.LinkedHashMap

enum class Orientation(val lc: String) { DEFAULT("default"), LANDSCAPE("landscape"), PORTRAIT("portrait") }
enum class DisplayCutout(val lc: String) { DEFAULT("default"), SHORT_EDGES("shortEdges"), NEVER("never"), ALWAYS("always") }

class KorgePluginsContainer(val project: Project, val parentClassLoader: ClassLoader = KorgePluginsContainer::class.java.classLoader) {
    val globalParams = LinkedHashMap<String, String>()
	val plugins = LinkedHashMap<MavenLocation, KorgePluginDescriptor>()

    val files by lazy { project.resolveArtifacts(*plugins.values.map { it.jvmArtifact }.toTypedArray()) }
    val urls by lazy { files.map { it.toURI().toURL() } }
    val classLoader by lazy {
		//println("KorgePluginsContainer.classLoader: $urls")
		URLClassLoader(urls.toTypedArray(), parentClassLoader)
	}

    val pluginExts = KorgePluginExtensions(project)

	fun addPlugin(artifact: MavenLocation): KorgePluginDescriptor {
		return plugins.getOrPut(artifact) { KorgePluginDescriptor(this, artifact) }
	}
}

data class KorgePluginDescriptor(val container: KorgePluginsContainer, val artifact: MavenLocation, val args: LinkedHashMap<String, String> = LinkedHashMap()) {
	val jvmArtifact = artifact.withNameSuffix("-jvm")
    val files by lazy { container.project.resolveArtifacts(jvmArtifact) }
    val urls by lazy { files.map { it.toURI().toURL() } }
    val classLoader by lazy { URLClassLoader(urls.toTypedArray(), container.parentClassLoader) }
	fun addArgs(args: Map<String, String>) = this.apply { this.args.putAll(args) }.apply { container.globalParams.putAll(args) }
}

fun String.replaceGroovy(replacements: Map<String, Any?>): String {
	//println("String.replaceGroovy: this=$this, replacements=$replacements")
	val templateEngine = SimpleTemplateEngine()
	val template = templateEngine.createTemplate(this)
	val replaced = template.make(replacements.toMutableMap())
	return replaced.toString()
}

data class MavenLocation(val group: String, val name: String, val version: String, val classifier: String? = null) {
	val versionWithClassifier by lazy { buildString {
		append(version)
		if (classifier != null) {
			append(':')
			append(classifier)
		}
	} }

	companion object {
        operator fun invoke(location: String): MavenLocation {
			val parts = location.split(":")
			return MavenLocation(parts[0], parts[1], parts[2], parts.getOrNull(3))
		}
	}

	fun withNameSuffix(suffix: String) = copy(name = "$name$suffix")

	val full: String by lazy { "$group:$name:$versionWithClassifier" }

	override fun toString(): String = full
}

@Suppress("unused")
open class KorgeExtension(
    @Inject val project: Project,
    //private val objectFactory: ObjectFactory
) {
    private var includeIndirectAndroid: Boolean = false
	internal fun init(includeIndirectAndroid: Boolean, projectType: ProjectType) {
	    this.includeIndirectAndroid = includeIndirectAndroid
        this.projectType = projectType
        this.project.afterEvaluate {
            implicitCheckVersion()
        }
	}

    companion object {
        const val ESBUILD_DEFAULT_VERSION = "0.17.10"

        val DEFAULT_ANDROID_EXCLUDE_PATTERNS = setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/license.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/notice.txt",
            "META-INF/LGPL*",
            "META-INF/AL2.0",
            "**/androidsupportmultidexversion.txt",
            "META-INF/versions/9/previous-compilation-data.bin",
        )

        val DEFAULT_ANDROID_INCLUDE_PATTERNS_LIBS = setOf(
            "META-INF/*.kotlin_module",
            "**/*.kotlin_metadata",
            "**/*.kotlin_builtins",
        )

        val validIdentifierRegexp = Regex("^[a-zA-Z_]\\w*$")

        fun isIdValid(id: String) = id.isNotEmpty() && id.isNotBlank() && id.split(".").all { it.matches(validIdentifierRegexp) }

        fun verifyId(id: String) {
            if (!isIdValid(id)) {
                throw InvalidNameException("'$id' is invalid. Should be separed by '.', shouldn't have spaces, and each component should not start by a number. Example: 'com.test.demo2'")
            }
        }
    }

    internal var targets = LinkedHashSet<String>()

    private fun target(name: String, block: () -> Unit) {
        if (!targets.contains(name)) {
            targets.add(name)
            block()
        }
    }

    // https://github.com/JetBrains/kotlin/pull/4339
    var mingwX64PatchedLegacyMemoryManager: Boolean = true
  
    lateinit var projectType: ProjectType
        private set

    private var _checkVersionOnce = false

    var autoGenerateTypedResources: Boolean = true

    /**
     * This checks that you are using the latest version of KorGE
     * once per day.
     *
     * This downloads the latest version and
     * optionally when [telemetry] is set to true, or gradle property `korge.disable.telemetry` is set to true
     * sends your current KorGE version, operating system, cpu architecture and a random, anonymous install UUID
     * for statistical purposes.
     *
     * In the case you want the plugin to also notify you that a new version is available,
     * you can set the [report] parameter to true.
     *
     * If you want to totally disable this check, you can by setting [check] to false.
     *
     * Have in mind that this check is a single small GET request once per day, it is anonymous,
     * doesn't send any kind of personal data, and it greatly helps us to have statistics of how many people is using KorGE
     * and which version. If you don't want to have the report once per day you can set report=false
     */
    fun checkVersion(check: Boolean = true, report: Boolean = true, telemetry: Boolean = project.findProperty("korge.disable.telemetry") != "true") {
        if (!_checkVersionOnce) {
            _checkVersionOnce = true
            if (check) {
                val thread = project.korgeCheckVersion(report, telemetry)
                project.afterEvaluate { thread.join() }
            }
        }
    }

    internal fun implicitCheckVersion() {
        checkVersion(check = true, report = false)
    }

    /**
     * Configures JVM target
     */
    fun targetJvm() {
        target("jvm") {
            project.configureJvm(projectType)
        }
    }

    /**
     * Configures JavaScript target
     */
    fun targetJs() {
        target("js") {
            project.configureJavaScript(projectType)
        }
    }

    /**
     * Configures WASM target
     */
    fun targetWasm(binaryen: Boolean = false) {
        target("wasm") {
            project.configureWasm(projectType, binaryen)
        }
    }

    /**
     * Configures Desktop targets depending on the host:
     *
     * - mingwX64
     * - linuxX64
     * - linuxArm64
     * - macosX64
     * - macosArm64
     */
    fun targetDesktop() {
        target("desktop") {
            if (supportKotlinNative) {
                project.configureNativeDesktop(projectType)
            }
        }
    }

    /**
     * Configure Desktop cross-targets:
     *
     * - Lima
     * - Wine
     */
    fun targetDesktopCross() {
        target("desktop-cross") {
            if (supportKotlinNative) {
                project.configureNativeDesktopCross()
            }
        }
    }

    /**
     * Configures Android indirect. Alias for [targetAndroidIndirect]
     */
    fun targetAndroid() {
        target("android") {
            project.configureAndroidDirect(projectType, isKorge = true)
        }
    }

    @Deprecated("", ReplaceWith("targetAndroid()")) fun targetAndroidIndirect() = targetAndroid()
    @Deprecated("", ReplaceWith("targetAndroid()")) fun targetAndroidDirect() = targetAndroid()

    /**
     * Configures Kotlin/Native iOS target (only on macOS)
     */
    fun targetIos() {
        target("ios") {
            if (isMacos && supportKotlinNative) {
                project.configureNativeIos(projectType)
            }
        }
    }

    /**
     * Uses gradle.properties and system environment variables to determine which targets to enable. JVM is always enabled.
     *
     * gradle.properties:
     * - korge.enable.desktop=true
     * - korge.enable.android=true
     * - korge.enable.ios=true
     * - korge.enable.js=true
     *
     * Environment Variables:
     * - KORGE_ENABLE_DESKTOP
     * - KORGE_ENABLE_ANDROID
     * - KORGE_ENABLE_ANDROID_IOS
     * - KORGE_ENABLE_ANDROID_JS
     */
    fun targetDefault() {
        if (newDesktopEnabled) targetDesktop()
        if (newAndroidEnabled) targetAndroid()
        //if (newAndroidIndirectEnabled) targetAndroidIndirect()
        //if (newAndroidDirectEnabled) targetAndroidDirect()
        if (newIosEnabled) targetIos()
        if (newJsEnabled) targetJs()
    }

    /**
     * Configure all the available targets unconditionally.
     */
    fun targetAll() {
        targetJvm()
        targetJs()
        targetDesktop()
        targetAndroid()
        targetIos()
    }

    /** Enables kotlinx.serialization */
    fun serialization() {
        project.plugins.apply("kotlinx-serialization")
        androidGradlePlugin("kotlinx-serialization")
        androidGradleClasspath("org.jetbrains.kotlin:kotlin-serialization:${BuildVersions.KOTLIN}")
    }

    /** Enables kotlinx.serialization and includes `org.jetbrains.kotlinx:kotlinx-serialization-json` */
    fun serializationJson() {
        serialization()
        project.dependencies.add("commonMainApi", "org.jetbrains.kotlinx:kotlinx-serialization-json:${BuildVersions.KOTLIN_SERIALIZATION}")
        androidGradleDependency("org.jetbrains.kotlinx:kotlinx-serialization-json:${BuildVersions.KOTLIN_SERIALIZATION}")
    }

    val resourceProcessors = arrayListOf<KorgeResourceProcessor>()

    fun addResourceProcessor(processor: KorgeResourceProcessor) {
        resourceProcessors += processor
    }

    //val bundles = KorgeBundles(project)

    //@JvmOverloads
    //fun bundle(uri: String, baseName: String? = null) = bundles.bundle(uri, baseName)

    val DEFAULT_JVM_TARGET = "1.8"
    //val DEFAULT_JVM_TARGET = "1.6"
	var jvmTarget: String = project.findProject("jvm.target")?.toString() ?: DEFAULT_JVM_TARGET
	var androidLibrary: Boolean = project.findProperty("android.library") == "true"
    var overwriteAndroidFiles: Boolean = project.findProperty("overwrite.android.files") == "false"
    var id: String = "com.unknown.unknownapp"
        get() = field
        set(value) {
            verifyId(value)
            field = value
        }
    var versionCode: Int = 1
	var version: String = "0.0.1"
    var preferredIphoneSimulatorVersion: Int = 8

	var exeBaseName: String = "app"

	var name: String = "unnamed"
    var title: String? = null
	var description: String = "description"
	var orientation: Orientation = Orientation.DEFAULT
    var displayCutout: DisplayCutout = DisplayCutout.DEFAULT

	var copyright: String = "Copyright (c) ${Year.now().value} Unknown"

    var sourceMaps: Boolean = false
	var supressWarnings: Boolean = false

    val versionSubstitutions = LinkedHashMap<String, String>().also {
        it["com.soywiz.korlibs.kbignum:kbignum"] = BuildVersions.KORLIBS
        it["com.soywiz.korlibs.kds:kds"] = BuildVersions.KORLIBS
        it["com.soywiz.korlibs.klock:klock"] = BuildVersions.KORLIBS
        it["com.soywiz.korlibs.klogger:klogger"] = BuildVersions.KORLIBS
        it["com.soywiz.korlibs.kmem:kmem"] = BuildVersions.KORLIBS
        it["com.soywiz.korlibs.korau:korau"] = BuildVersions.KORLIBS
        it["com.soywiz.korlibs.korge2:korge"] = BuildVersions.KORLIBS
        it["com.soywiz.korlibs.korge.plugins:korge-gradle-plugin"] = BuildVersions.KORLIBS
        it["com.soywiz.korlibs.korge.reloadagent:korge-reload-agent"] = BuildVersions.KORLIBS
        it["com.soywiz.korlibs.korgw:korgw"] = BuildVersions.KORLIBS
        it["com.soywiz.korlibs.korim:korim"] = BuildVersions.KORLIBS
        it["com.soywiz.korlibs.korinject:korinject"] = BuildVersions.KORLIBS
        it["com.soywiz.korlibs.korio:korio"] = BuildVersions.KORLIBS
        it["com.soywiz.korlibs.korma:korma"] = BuildVersions.KORLIBS
        it["com.soywiz.korlibs.korte:korte"] = BuildVersions.KORLIBS
        it["com.soywiz.korlibs.krypto:krypto"] = BuildVersions.KORLIBS
        it["com.soywiz.korlibs.ktruth:ktruth"] = BuildVersions.KORLIBS
    }

    fun versionSubstitution(groupName: String, version: String) {
        versionSubstitutions[groupName] = version
    }

    /**
     * Determines whether the standard console will be available on Windows or not
     * by setting the windows subsystem to console or windows.
     *
     * When set to null, on debug builds it will include open console, and on release builds it won't open any console.
     */
    var enableConsole: Boolean? = null

	var authorName = "unknown"
	var authorEmail = "unknown@unknown"
	var authorHref = "http://localhost"

	val nativeEnabled = (project.findProperty("disable.kotlin.native") != "true") && (System.getenv("DISABLE_KOTLIN_NATIVE") != "true")

    val newDesktopEnabled get() = project.findProperty("korge.enable.desktop") == "true" || System.getenv("KORGE_ENABLE_DESKTOP") == "true"
    val newAndroidEnabled get() = project.findProperty("korge.enable.android") == "true" || System.getenv("KORGE_ENABLE_ANDROID") == "true"
    //val newAndroidIndirectEnabled get() = project.findProperty("korge.enable.android.indirect") == "true" || System.getenv("KORGE_ENABLE_ANDROID_INDIRECT") == "true"
    //val newAndroidDirectEnabled get() = project.findProperty("korge.enable.android.direct") == "true" || System.getenv("KORGE_ENABLE_ANDROID_DIRECT") == "true"
    val newIosEnabled get() = project.findProperty("korge.enable.ios") == "true" || System.getenv("KORGE_ENABLE_IOS") == "true"
    val newJsEnabled get() = project.findProperty("korge.enable.js") == "true" || System.getenv("KORGE_ENABLE_JS") == "true"

    var searchResourceProcessorsInMainSourceSet: Boolean = false

    var icon: File? = project.projectDir["icon.png"]
    var banner: File? = project.projectDir["banner.png"]

    var javaAddOpens: List<String> = JvmAddOpens.createAddOpens().toMutableList()

	var gameCategory: GameCategory? = null

	var fullscreen: Boolean? = null

	var backgroundColor: Int = 0xff000000.toInt()

	var webBindAddress = project.findProperty("web.bind.address")?.toString() ?: "0.0.0.0"
	var webBindPort = project.findProperty("web.bind.port")?.toString()?.toIntOrNull() ?: 0

	var appleDevelopmentTeamId: String? = java.lang.System.getenv("DEVELOPMENT_TEAM")
		?: java.lang.System.getProperty("appleDevelopmentTeamId")?.toString()
		?: project.findProperty("appleDevelopmentTeamId")?.toString()

	var appleOrganizationName = "User Name Name"

	var entryPoint: String? = null
    //val jvmMainClassNameProp: Property<String> = objectFactory.property(String::class.java).also { it.set("MainKt") }
	var jvmMainClassName: String = "MainKt"
        //get() = jvmMainClassNameProp.get()
        //set(value) { jvmMainClassNameProp.set(value) }
	//var proguardObfuscate: Boolean = false
	var proguardObfuscate: Boolean = true

	val realEntryPoint get() = entryPoint ?: (jvmMainClassName.substringBeforeLast('.', "") + ".main").trimStart('.')
	val realJvmMainClassName: String get() = jvmMainClassName

	val extraEntryPoints = arrayListOf<Entrypoint>()

    internal fun getDefaultEntryPoint(): Entrypoint {
        return Entrypoint("") { realJvmMainClassName }
    }

    internal fun getAllEntryPoints(): List<Entrypoint> {
        return listOf(getDefaultEntryPoint()) + extraEntryPoints
    }

	class Entrypoint(val name: String, val jvmMainClassName: () -> String) {
		val entryPoint by lazy { (jvmMainClassName().substringBeforeLast('.', "") + ".main").trimStart('.') }
	}

	fun entrypoint(name: String, jvmMainClassName: String) {
		extraEntryPoints.add(Entrypoint(name) { jvmMainClassName })
	}

    var esbuildVersion: String = ESBUILD_DEFAULT_VERSION

    var androidMinSdk: Int = ANDROID_DEFAULT_MIN_SDK
	var androidCompileSdk: Int = ANDROID_DEFAULT_COMPILE_SDK
	var androidTargetSdk: Int = ANDROID_DEFAULT_TARGET_SDK

    var androidTimeoutMs: Int = 30 * 1000

    var androidExcludePatterns: Set<String> = DEFAULT_ANDROID_EXCLUDE_PATTERNS

	fun androidSdk(compileSdk: Int, minSdk: Int, targetSdk: Int) {
		androidMinSdk = minSdk
		androidCompileSdk = compileSdk
		androidTargetSdk = targetSdk
	}

	internal var _androidAppendBuildGradle: String? = null

	fun androidAppendBuildGradle(str: String) {
		if (_androidAppendBuildGradle == null) {
			_androidAppendBuildGradle = ""
		}
		_androidAppendBuildGradle += str
	}

	val configs = LinkedHashMap<String, String>()

	fun config(name: String, value: String) {
		configs[name] = value
	}

	val plugins = KorgePluginsContainer(project)
    val androidGradlePlugins = LinkedHashSet<String>()
    val androidGradleDependencies = LinkedHashSet<String>()
    val androidGradleClasspaths = LinkedHashSet<String>()
	val androidManifestApplicationChunks = LinkedHashSet<String>()
	val androidManifestChunks = LinkedHashSet<String>()
    var androidMsaa: Int? = null

    fun plugin(name: String, args: Map<String, String> = mapOf()) {
		dependencyMulti(name, registerPlugin = false)
        plugins.addPlugin(MavenLocation(name)).addArgs(args)
    }

	internal val defaultPluginsClassLoader by lazy { plugins.classLoader }

    var androidReleaseSignStoreFile: String = "build/korge.keystore"
    var androidReleaseSignStorePassword: String = "password"
    var androidReleaseSignKeyAlias: String = "korge"
    var androidReleaseSignKeyPassword: String = "password"

    var iosDevelopmentTeam: String? = null

	// Already included in core
	fun supportExperimental3d() = Unit
	fun support3d() = Unit

	//<uses-permission android:name="android.permission.VIBRATE" />
	fun androidManifestApplicationChunk(text: String) {
		androidManifestApplicationChunks += text
	}

    fun androidGradlePlugin(name: String) {
        androidGradlePlugins += name
    }

    fun androidGradleClasspath(name: String) {
        androidGradleClasspaths += name
    }

    fun androidGradleDependency(dependency: String) {
        androidGradleDependencies += dependency
    }

    fun androidManifestChunk(text: String) {
		androidManifestChunks += text
	}

	fun androidPermission(name: String) {
		androidManifestChunk("""<uses-permission android:name="$name" />""")
	}

	fun supportVibration() {
		androidPermission("android.permission.VIBRATE")
	}

    //@Deprecated("")
    //fun admob(ADMOB_APP_ID: String) {
    //    bundle("https://github.com/korlibs/korge-bundles.git::korge-admob::4ac7fcee689e1b541849cedd1e017016128624b9##2ca2bf24ab19e4618077f57092abfc8c5c8fba50b2797a9c6d0e139cd24d8b35")
    //    config("ADMOB_APP_ID", ADMOB_APP_ID)
    //}
    //
    //@Deprecated("")
    //fun gameServices() {
    //    bundle("https://github.com/korlibs/korge-bundles.git::korge-services::4ac7fcee689e1b541849cedd1e017016128624b9##392d5ed87428c7137ae40aa7a44f013dd1d759630dca64e151bbc546eb25e28e")
    //}
    //
    //@Deprecated("")
    //fun billing() {
    //    bundle("https://github.com/korlibs/korge-bundles.git::korge-billing::4ac7fcee689e1b541849cedd1e017016128624b9##cbde3d386e8d792855b7ef64e5e700f43b7bb367aedc6a27198892e41d50844b")
    //}

    fun author(name: String, email: String, href: String) {
		authorName = name
		authorEmail = email
		authorHref = href
	}

	/////////////////////////////////////////////////
	/////////////////////////////////////////////////


	fun dependencyProject(name: String) = project {
		dependencies {
			add("commonMainApi", project(name))
			add("commonTestImplementation", project(name))
		}
	}

	val ALL_NATIVE_TARGETS by lazy { listOf("iosArm64", "iosX64", "iosSimulatorArm64") + project.DESKTOP_NATIVE_TARGETS }
	//val ALL_TARGETS = listOf("android", "js", "jvm", "metadata") + ALL_NATIVE_TARGETS
	val ALL_TARGETS by lazy { listOf("js", "jvm", "metadata") + ALL_NATIVE_TARGETS }

	@JvmOverloads
	fun dependencyMulti(group: String, name: String, version: String, targets: List<String> = ALL_TARGETS, suffixCommonRename: Boolean = false, androidIsJvm: Boolean = false): Unit = project {
		project.dependencies {
			//println("dependencyMulti --> $group:$name:$version")
            add("commonMainApi", "$group:$name:$version")
		}
        Unit
	}

	@JvmOverloads
	fun dependencyMulti(dependency: String, targets: List<String> = ALL_TARGETS, registerPlugin: Boolean = true) {
		val location = MavenLocation(dependency)
		if (registerPlugin) plugin(location.full)
		return dependencyMulti(location.group, location.name, location.versionWithClassifier, targets)
	}

	/*
	@JvmOverloads
	fun dependencyNodeModule(name: String, version: String) = project {
		val node = extensions.getByType(NodeExtension::class.java)

		val installNodeModule = tasks.createThis<NpmTask>("installJs${name.capitalize()}") {
			onlyIf { !File(node.nodeModulesDir, name).exists() }
			setArgs(arrayListOf("install", "$name@$version"))
		}

		tasks.getByName("jsTestNode").dependsOn(installNodeModule)
	}
	*/

	data class CInteropTargets(val name: String, val targets: List<String>)

	val cinterops = arrayListOf<CInteropTargets>()


	fun dependencyCInterops(name: String, targets: List<String>) = project {
		cinterops += CInteropTargets(name, targets)
		for (target in targets) {
			((kotlin.targets.findByName(target) as AbstractKotlinTarget).compilations["main"] as KotlinNativeCompilation).apply {
				cinterops.apply {
					maybeCreate(name).apply {
					}
				}
			}
		}
	}

	@JvmOverloads
	fun dependencyCInteropsExternal(dependency: String, cinterop: String, targets: List<String> = ALL_NATIVE_TARGETS) {
		dependencyMulti("$dependency:cinterop-$cinterop@klib", targets)
	}

	fun addDependency(config: String, notation: String) {
		val cfg = project.configurations.findByName(config)
		if (cfg == null) {
			// @TODO: Turkish hack. This doesn't seems right. Probably someone messed something up.
			if (config.endsWith("Implementation")) {
				val config2 = config.removeSuffix("Implementation") + "İmplementation"
				println("Can't find config: $config . Trying: $config2 (Turkish hack)")
				return addDependency(config2, notation)
			}

			for (rcfg in project.configurations) {
				println("CONFIGURATION: ${rcfg.name}")
			}
			error("Can't find configuration '$config'")
		}
		project.dependencies.add(config, notation)
	}

}

// println(project.resolveArtifacts("korlibs.korge:korge-metadata:1.0.0"))
fun Project.resolveArtifacts(vararg artifacts: String): Set<File> {
    val config = project.configurations.detachedConfiguration(
        *artifacts.map {
            (project.dependencies.create(it) as ExternalModuleDependency).apply {
                targetConfiguration = "default"
            }
        }.toTypedArray()
    ).apply {
        isTransitive = false
    }
    return config.files
}

fun Project.resolveArtifacts(vararg artifacts: MavenLocation): Set<File> =
	resolveArtifacts(*artifacts.map { it.full }.toTypedArray())

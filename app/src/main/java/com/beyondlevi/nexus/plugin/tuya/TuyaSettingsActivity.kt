package com.beyondlevi.nexus.plugin.tuya

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.client.ui.NexusUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TuyaSettingsActivity : Activity() {
    private val settings by lazy { TuyaSettings(applicationContext) }
    private val repository by lazy { TuyaRepository(applicationContext) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var checkJob: Job? = null

    private lateinit var accessIdField: EditText
    private lateinit var accessSecretField: EditText
    private lateinit var uidField: EditText
    private lateinit var regionValue: TextView
    private lateinit var statusMessage: TextView

    private var region: TuyaRegion = TuyaRegion.WESTERN_AMERICA

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        region = settings.region
        buildUi()
    }

    override fun onPause() {
        persist()
        super.onPause()
    }

    override fun onDestroy() {
        checkJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        accessIdField = NexusUi.field(this, "Access ID / Client ID").apply {
            setText(settings.accessId)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        accessSecretField = NexusUi.field(this, "Access Secret").apply {
            setText(settings.accessSecret)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        uidField = NexusUi.field(this, "Account UID (optional)").apply {
            setText(settings.uid)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        regionValue = NexusUi.rowValue(this).apply { text = region.label }
        statusMessage = NexusUi.statusLine(this).apply {
            text = if (settings.isConfigured) {
                "Keys saved. Test the connection to confirm."
            } else {
                "Paste the keys from your Tuya IoT Platform cloud project."
            }
        }

        val content = NexusUi.contentColumn(this).apply {
            addView(
                NexusUi.cardBody(
                    this@TuyaSettingsActivity,
                    "Control the homes, rooms and devices of your Tuya / Smart Life " +
                        "account from the glasses HUD.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@TuyaSettingsActivity, 18))
            addView(NexusUi.sectionRow(this@TuyaSettingsActivity, "Cloud project"), NexusUi.block())
            addView(BusTheme.gap(this@TuyaSettingsActivity, 10))
            addView(credentialsCard(), NexusUi.block())
            addView(BusTheme.gap(this@TuyaSettingsActivity, 12))
            addView(testCard(), NexusUi.block())
            addView(BusTheme.gap(this@TuyaSettingsActivity, 10))
            addView(statusMessage, NexusUi.block())
            addView(BusTheme.gap(this@TuyaSettingsActivity, 24))
            addView(NexusUi.sectionRow(this@TuyaSettingsActivity, "Plugin"), NexusUi.block())
            addView(BusTheme.gap(this@TuyaSettingsActivity, 10))
            addView(uninstallRow(), NexusUi.block())
        }

        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@TuyaSettingsActivity,
                    NexusPluginIcons.drawableFor(ICON_KEY),
                    "Tuya Smart Home",
                    "Homes · rooms · devices · v${versionName()}",
                ),
                NexusUi.block(),
            )
            addView(
                NexusUi.screen(this@TuyaSettingsActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
    }

    private fun credentialsCard(): LinearLayout = NexusUi.card(this).apply {
        addView(NexusUi.rowLabel(this@TuyaSettingsActivity, "Access ID"), NexusUi.block())
        addView(accessIdField, NexusUi.block())
        addView(BusTheme.gap(this@TuyaSettingsActivity, 10))
        addView(NexusUi.rowLabel(this@TuyaSettingsActivity, "Access Secret"), NexusUi.block())
        addView(accessSecretField, NexusUi.block())
        addView(BusTheme.gap(this@TuyaSettingsActivity, 10))
        addView(NexusUi.divider(this@TuyaSettingsActivity))
        addView(BusTheme.gap(this@TuyaSettingsActivity, 10))
        addView(regionRow(), NexusUi.block())
        addView(BusTheme.gap(this@TuyaSettingsActivity, 10))
        addView(NexusUi.rowLabel(this@TuyaSettingsActivity, "Account UID"), NexusUi.block())
        addView(uidField, NexusUi.block())
        addView(BusTheme.gap(this@TuyaSettingsActivity, 6))
        addView(
            NexusUi.rowSub(
                this@TuyaSettingsActivity,
                "Leave empty to resolve it from a linked device.",
            ),
            NexusUi.block(),
        )
    }

    private fun regionRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            NexusUi.rowLabel(this@TuyaSettingsActivity, "Data center"),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(regionValue)
        addView(
            NexusUi.textButton(this@TuyaSettingsActivity, "Change").apply {
                setOnClickListener { cycleRegion() }
            },
        )
    }

    private fun cycleRegion() {
        val all = TuyaRegion.entries
        region = all[(all.indexOf(region) + 1) % all.size]
        regionValue.text = region.label
        persist()
    }

    private fun testCard(): LinearLayout = NexusUi.pressableCard(this).apply {
        addView(
            LinearLayout(this@TuyaSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(NexusUi.rowTitle(this@TuyaSettingsActivity, "Test connection"))
                addView(BusTheme.gap(this@TuyaSettingsActivity, 4))
                addView(
                    NexusUi.rowSub(
                        this@TuyaSettingsActivity,
                        "Signs in and counts the homes and devices it can see.",
                    ),
                )
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            NexusUi.textButton(this@TuyaSettingsActivity, "Test").apply {
                setOnClickListener { runCheck() }
            },
        )
        setOnClickListener { runCheck() }
    }

    private fun runCheck() {
        persist()
        if (!settings.isConfigured) {
            statusMessage.text = "Fill in the Access ID and Access Secret first."
            return
        }
        checkJob?.cancel()
        statusMessage.text = "Contacting ${region.label}..."
        checkJob = scope.launch {
            val text = try {
                repository.connectionCheck()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: TuyaApiException) {
                "Tuya refused the request (${failure.apiCode}): ${failure.message.orEmpty().take(120)}"
            } catch (failure: Throwable) {
                failure.message?.take(160) ?: "Could not reach Tuya."
            }
            statusMessage.text = text
        }
    }

    private fun persist() {
        settings.accessId = accessIdField.text.toString()
        settings.accessSecret = accessSecretField.text.toString()
        settings.uid = uidField.text.toString()
        settings.region = region
        repository.invalidate()
    }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrNull().orEmpty().ifBlank { "?" }

    private fun uninstallRow() = NexusUi.uninstallCard(this, "Tuya Smart Home") {
        // Rebuilding synchronously inside a click tears down the dispatching view.
        handler.post {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
        }
    }

    private companion object {
        const val ICON_KEY = "bolt"
    }
}

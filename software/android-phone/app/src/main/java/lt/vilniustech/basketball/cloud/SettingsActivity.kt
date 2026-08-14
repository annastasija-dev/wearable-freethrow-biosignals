package lt.vilniustech.basketball.cloud

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lt.vilniustech.basketball.cloud.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.settingsToolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)

        binding.baseUrlInput.setText(AppConfig.cloudBaseUrl(this))
        binding.apiKeyInput.setText(AppConfig.apiKey(this))

        binding.discoverUrlsButton.setOnClickListener { discoverCloudUrls() }
        binding.testCloudButton.setOnClickListener { testCloudConnection() }
        binding.closeButton.setOnClickListener { finish() }

        lifecycleScope.launch {
            val bootstrap = withContext(Dispatchers.IO) {
                CloudUrlResolver.bootstrap(this@SettingsActivity)
            }
            if (bootstrap.repaired) {
                binding.baseUrlInput.setText(bootstrap.workingUrl)
                toast(bootstrap.message)
            }
        }

        binding.saveButton.setOnClickListener {
            val baseUrl = binding.baseUrlInput.text.toString().trim()
            val apiKey = binding.apiKeyInput.text.toString().trim()
            if (baseUrl.isEmpty()) {
                toast(getString(R.string.error_base_url))
                return@setOnClickListener
            }
            AppConfig.save(this, baseUrl, apiKey)
            toast(getString(R.string.settings_saved))
            finish()
        }
    }

    private fun discoverCloudUrls() {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    CloudUrlResolver.bootstrap(this@SettingsActivity)
                }
            }.onSuccess { bootstrap ->
                binding.baseUrlInput.setText(bootstrap.workingUrl)
                binding.discoveredUrlsContainer.removeAllViews()
                toast(bootstrap.message)
            }.onFailure {
                toast(getString(R.string.discover_urls_fail, it.message ?: "unknown"))
            }
        }
    }

    private fun testCloudConnection() {
        val baseUrl = binding.baseUrlInput.text.toString().trim()
        val apiKey = binding.apiKeyInput.text.toString().trim()
        if (baseUrl.isEmpty()) {
            toast(getString(R.string.error_base_url))
            return
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    CloudApiClient(baseUrl, apiKey).healthCheck()
                }
            }.onSuccess {
                toast(getString(R.string.cloud_ok))
            }.onFailure {
                toast(getString(R.string.cloud_fail, it.message ?: "unknown"))
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

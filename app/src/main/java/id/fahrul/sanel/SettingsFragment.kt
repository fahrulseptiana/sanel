package id.fahrul.sanel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.PermissionChecker
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import id.fahrul.sanel.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private var advancedVisible = false
    private val TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND"

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            SettingsManager.termuxPermissionGranted = true
            binding.cbTermuxPermission.isChecked = true
            binding.cbTermuxPermission.isEnabled = false
            binding.tvTermuxStatus.text = "✓ Permission granted"
            Toast.makeText(requireContext(), "Termux permission granted", Toast.LENGTH_SHORT).show()
        } else {
            SettingsManager.termuxPermissionGranted = false
            binding.cbTermuxPermission.isChecked = false
            binding.tvTermuxStatus.text = "Permission denied. Grant it in App Settings → Permissions."
            Toast.makeText(requireContext(), "Termux permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadSettings()

        binding.etEndpoint.doAfterTextChanged {
            SettingsManager.endpoint = it.toString()
        }
        binding.etApiKey.doAfterTextChanged {
            SettingsManager.apiKey = it.toString()
        }
        binding.etModel.doAfterTextChanged {
            SettingsManager.model = it.toString()
        }

        binding.sliderTemperature.addOnChangeListener { _, value, _ ->
            binding.tvTemperatureValue.text = "%.1f".format(value)
            SettingsManager.temperature = value
        }

        binding.etMaxTokens.doAfterTextChanged {
            val v = it.toString().toIntOrNull()
            if (v != null) SettingsManager.maxTokens = v
        }

        binding.switchThinking.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.thinkingEnabled = isChecked
            binding.inputThinkingBudget.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.etThinkingBudget.doAfterTextChanged {
            val v = it.toString().toIntOrNull()
            if (v != null) SettingsManager.thinkingBudget = v
        }

        binding.advancedHeader.setOnClickListener {
            advancedVisible = !advancedVisible
            binding.advancedPanel.visibility = if (advancedVisible) View.VISIBLE else View.GONE
            binding.ivChevron.rotation = if (advancedVisible) 180f else 0f
        }

        binding.switchTermux.isChecked = SettingsManager.termuxEnabled
        binding.switchTermux.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.termuxEnabled = isChecked
        }

        binding.cbTermuxPermission.isChecked = SettingsManager.termuxPermissionGranted
        binding.cbTermuxPermission.isEnabled = !SettingsManager.termuxPermissionGranted
        updateTermuxStatus()

        binding.cbTermuxPermission.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requestTermuxPermission()
            } else {
                SettingsManager.termuxPermissionGranted = false
                binding.cbTermuxPermission.isEnabled = true
                updateTermuxStatus()
            }
        }
    }

    private fun requestTermuxPermission() {
        // Check if already granted
        val granted = PermissionChecker.checkSelfPermission(
            requireContext(), TERMUX_PERMISSION
        ) == PermissionChecker.PERMISSION_GRANTED

        if (granted) {
            SettingsManager.termuxPermissionGranted = true
            binding.tvTermuxStatus.text = "✓ Permission granted"
            return
        }

        // Check if Termux is installed
        val termuxInstalled = try {
            requireContext().packageManager.getPackageInfo("com.termux", 0)
            true
        } catch (_: Exception) { false }

        if (!termuxInstalled) {
            binding.cbTermuxPermission.isChecked = false
            binding.tvTermuxStatus.text = "Termux not installed. Install from F-Droid."
            return
        }

        // Request the permission — system will show dialog
        permissionLauncher.launch(TERMUX_PERMISSION)
    }

    private fun updateTermuxStatus() {
        binding.tvTermuxStatus.text = when {
            SettingsManager.termuxPermissionGranted -> "✓ Permission granted"
            else -> "Check this to request permission from Termux"
        }
    }

    private fun loadSettings() {
        binding.etEndpoint.setText(SettingsManager.endpoint)
        binding.etApiKey.setText(SettingsManager.apiKey)
        binding.etModel.setText(SettingsManager.model)
        binding.sliderTemperature.value = SettingsManager.temperature
        binding.tvTemperatureValue.text = "%.1f".format(SettingsManager.temperature)
        binding.etMaxTokens.setText(SettingsManager.maxTokens.toString())
        binding.switchThinking.isChecked = SettingsManager.thinkingEnabled
        binding.etThinkingBudget.setText(SettingsManager.thinkingBudget.toString())
        binding.inputThinkingBudget.visibility =
            if (SettingsManager.thinkingEnabled) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

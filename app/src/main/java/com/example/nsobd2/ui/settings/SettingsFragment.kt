package com.example.nsobd2.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.nsobd2.R
import com.example.nsobd2.viewmodel.VehicleViewModel
import kotlinx.coroutines.launch

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private val viewModel: VehicleViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val tvVin = view.findViewById<TextView>(R.id.tvVin)
        val btnReadVin = view.findViewById<Button>(R.id.btnReadVin)
        val btnCopyVin = view.findViewById<Button>(R.id.btnCopyVin)

        btnReadVin.setOnClickListener {
            if (!viewModel.connection.isConnected) {
                Toast.makeText(requireContext(), "Önce ELM327'ye bağlan!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.readVin()
        }

        btnCopyVin.setOnClickListener {
            val vin = tvVin.text.toString()
            if (vin.length >= 10) {
                val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("VIN", vin))
                Toast.makeText(requireContext(), "VIN kopyalandı", Toast.LENGTH_SHORT).show()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.vin.collect { tvVin.text = it }
            }
        }
    }
}
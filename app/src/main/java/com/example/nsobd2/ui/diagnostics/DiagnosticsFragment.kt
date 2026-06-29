package com.example.nsobd2.ui.diagnostics

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
import com.example.nsobd2.data.ParseResult
import com.example.nsobd2.viewmodel.AppScreen
import com.example.nsobd2.viewmodel.VehicleViewModel
import kotlinx.coroutines.launch

class DiagnosticsFragment : Fragment(R.layout.fragment_diagnostics) {

    private val viewModel: VehicleViewModel by activityViewModels()

    override fun onResume() {
        super.onResume()
        viewModel.setActiveScreen(AppScreen.DIAGNOSTICS)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnRead = view.findViewById<Button>(R.id.btnReadDtc)
        val btnClear = view.findViewById<Button>(R.id.btnClearDtc)
        val tvReadiness = view.findViewById<TextView>(R.id.tvReadiness)
        val tvActiveDtc = view.findViewById<TextView>(R.id.tvActiveDtc)
        val tvPendingDtc = view.findViewById<TextView>(R.id.tvPendingDtc)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.dtcResult.collect { result ->
                        tvActiveDtc.text = if (result is ParseResult.Success) result.value else "Hata/Veri Yok"
                    }
                }
                launch {
                    viewModel.pendingDtcResult.collect { result ->
                        tvPendingDtc.text = if (result is ParseResult.Success) result.value else "Hata/Veri Yok"
                    }
                }
                launch {
                    viewModel.readinessResult.collect { result ->
                        tvReadiness.text = if (result is ParseResult.Success) result.value else "Hata/Veri Yok"
                    }
                }
            }
        }

        btnRead.setOnClickListener {
            if (viewModel.connection.isConnected) {
                Toast.makeText(context, "Beyin okunuyor, lütfen bekleyin...", Toast.LENGTH_SHORT).show()
                viewModel.readAllFaults()
            } else {
                Toast.makeText(context, "Önce araca bağlanmalısın!", Toast.LENGTH_SHORT).show()
            }
        }

        btnClear.setOnClickListener {
            if (viewModel.connection.isConnected) {
                Toast.makeText(context, "Silme isteği gönderiliyor...", Toast.LENGTH_SHORT).show()
                viewModel.clearFaults(requireContext())
            } else {
                Toast.makeText(context, "Önce araca bağlanmalısın!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
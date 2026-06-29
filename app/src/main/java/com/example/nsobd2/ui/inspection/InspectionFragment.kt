package com.example.nsobd2.ui.inspection

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

class InspectionFragment : Fragment(R.layout.fragment_inspection) {

    private val viewModel: VehicleViewModel by activityViewModels()

    override fun onResume() {
        super.onResume()
        // Beyne haber ver: Muayene ekranındayız, canlı veriyi durdur!
        viewModel.setActiveScreen(AppScreen.INSPECTION)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnCheck = view.findViewById<Button>(R.id.btnCheckInspection)
        val tvResult = view.findViewById<TextView>(R.id.tvInspectionResult)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.readinessResult.collect { result ->
                    if (result is ParseResult.Success) {
                        tvResult.text = result.value
                        // Eğer ağır kusur yazıyorsa rengi kırmızı yap, geçer yazıyorsa yeşil
                        if (result.value.contains("AĞIR KUSUR")) {
                            tvResult.setTextColor(android.graphics.Color.parseColor("#F44336"))
                        } else if (result.value.contains("GEÇER")) {
                            tvResult.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                        } else {
                            tvResult.setTextColor(android.graphics.Color.WHITE)
                        }
                    } else {
                        tvResult.text = "Sonuç alınamadı."
                    }
                }
            }
        }

        btnCheck.setOnClickListener {
            if (viewModel.connection.isConnected) {
                Toast.makeText(context, "Emisyon ve Beyin sorgulanıyor...", Toast.LENGTH_SHORT).show()
                viewModel.readReadinessForInspection()
            } else {
                Toast.makeText(context, "Önce araca bağlanmalısın!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
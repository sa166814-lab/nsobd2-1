package com.example.nsobd2.ui.tests

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
import com.example.nsobd2.viewmodel.AppScreen
import com.example.nsobd2.viewmodel.VehicleViewModel
import kotlinx.coroutines.launch

class TestsFragment : Fragment(R.layout.fragment_tests) {

    private val viewModel: VehicleViewModel by activityViewModels()
    private lateinit var tvAccelStatus: TextView
    private lateinit var tvAccelTime: TextView
    private lateinit var tvAccelBest: TextView
    private lateinit var btnAccelStart: Button

    override fun onResume() {
        super.onResume()
        viewModel.setActiveScreen(AppScreen.TESTS)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvAccelStatus = view.findViewById(R.id.tvAccelStatus)
        tvAccelTime = view.findViewById(R.id.tvAccelTime)
        tvAccelBest = view.findViewById(R.id.tvAccelBest)
        btnAccelStart = view.findViewById(R.id.btnAccelStart)

        btnAccelStart.setOnClickListener {
            val state = viewModel.accelTestState.value
            if (state == VehicleViewModel.AccelTestState.WAITING_ZERO ||
                state == VehicleViewModel.AccelTestState.RUNNING) {
                viewModel.cancelAccelTest()
            } else {
                if (!viewModel.connection.isConnected) {
                    Toast.makeText(requireContext(), "Önce ELM327'ye bağlan!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                viewModel.startAccelTest()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.accelTestState.collect { state ->
                    when (state) {
                        VehicleViewModel.AccelTestState.IDLE -> {
                            tvAccelStatus.text = "HAZIR"
                            btnAccelStart.text = "🏁 TESTİ BAŞLAT"
                            btnAccelStart.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#388E3C"))
                        }
                        VehicleViewModel.AccelTestState.WAITING_ZERO -> {
                            tvAccelStatus.text = "🚗 ARAÇ DURMASINI BEKLİYOR... (HIZ 0)"
                            btnAccelStart.text = "❌ İPTAL"
                            btnAccelStart.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#D32F2F"))
                        }
                        VehicleViewModel.AccelTestState.RUNNING -> {
                            tvAccelStatus.text = "🔥 GAZA YÜKLEN!"
                            btnAccelStart.text = "❌ İPTAL"
                        }
                        VehicleViewModel.AccelTestState.FINISHED -> {
                            tvAccelStatus.text = "✅ BİTTİ!"
                            btnAccelStart.text = "🔁 TEKRAR"
                            btnAccelStart.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#388E3C"))
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.accelTestTime.collect { t ->
                    tvAccelTime.text = "%.2f".format(t)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.accelTestBest.collect { b ->
                    if (b > 0.0) tvAccelBest.text = "🏆 En İyi: %.2f sn".format(b)
                }
            }
        }
    }
}
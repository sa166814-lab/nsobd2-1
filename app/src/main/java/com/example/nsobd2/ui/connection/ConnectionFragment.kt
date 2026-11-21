package com.example.nsobd2.ui.connection

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.nsobd2.R
import com.example.nsobd2.viewmodel.AppScreen
import com.example.nsobd2.viewmodel.VehicleViewModel
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class ConnectionFragment : Fragment(R.layout.fragment_connection) {

    private val viewModel: VehicleViewModel by activityViewModels()

    // --- SON DOKUNUŞ BURASI: Sekme açıldığında beyni dinlenmeye alır ---
    override fun onResume() {
        super.onResume()
        viewModel.setActiveScreen(AppScreen.CONNECTION)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // XML'deki ID'lere göre eşleştirme yapıyoruz
        val etIp = view.findViewById<TextInputEditText>(R.id.etIpAddress)
        val etPort = view.findViewById<TextInputEditText>(R.id.etPort)
        val btnConnect = view.findViewById<Button>(R.id.btnConnectWifi)
        val btnDisconnect = view.findViewById<Button>(R.id.btnDisconnectWifi)

        // KRİTİK: Senin XML'deki ID'n tvWifiStatus olduğu için burada onu arıyoruz
        val tvStatusDisplay = view.findViewById<TextView>(R.id.tvWifiStatus)

        // 1. BAĞLANTI DURUMUNU İZLE (StateFlow Dinleyicisi)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.connectionStatus.collect { status ->
                    tvStatusDisplay.text = "Durum: $status"

                    if (status.contains("Başarılı")) {
                        tvStatusDisplay.setTextColor(Color.GREEN)
                        btnConnect.isEnabled = false // Bağlıyken tekrar basılmasın
                    } else {
                        tvStatusDisplay.setTextColor(Color.RED)
                        btnConnect.isEnabled = true
                    }
                }
            }
        }

        // 2. BAĞLAN BUTONU
        btnConnect.setOnClickListener {
            val ip = etIp.text.toString().trim().ifEmpty { "192.168.0.10" }
            val port = etPort.text.toString().trim().toIntOrNull() ?: 35000

            // ViewModel artık hem bağlanmayı hem de veri çekmeyi kendi hallediyor
            viewModel.connectToVehicle(ip, port)
        }

        // 3. BAĞLANTIYI KES BUTONU
        btnDisconnect.setOnClickListener {
            viewModel.disconnectFromVehicle(requireContext())
        }
    }
}
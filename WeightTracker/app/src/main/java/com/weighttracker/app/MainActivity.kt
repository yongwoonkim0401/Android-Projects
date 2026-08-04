package com.weighttracker.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupActionBarWithNavController
import com.weighttracker.app.databinding.ActivityMainBinding
import com.weighttracker.app.ui.home.HomeViewModel
import androidx.activity.viewModels

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        setupActionBarWithNavController(navHost.navController)

        // 외부에서 CSV 파일 공유로 들어온 경우 처리
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_VIEW) {
            val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            } ?: intent.data ?: return
            viewModel.importCsv(this, uri) { count ->
                Toast.makeText(this, "CSV에서 ${count}개 항목 가져옴", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        return navHost.navController.navigateUp() || super.onSupportNavigateUp()
    }
}

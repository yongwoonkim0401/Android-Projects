package com.weighttracker.app.ui.chart

import android.graphics.Color
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import com.weighttracker.app.R
import com.weighttracker.app.data.model.WeightEntry
import com.weighttracker.app.databinding.FragmentChartBinding
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class ChartFragment : Fragment() {

    private var _binding: FragmentChartBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ChartViewModel by viewModels()

    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val shortFmt = DateTimeFormatter.ofPattern("MM/dd")
    private val monthLabelFmt = DateTimeFormatter.ofPattern("yy.MM.dd")
    private val yearLabelFmt = DateTimeFormatter.ofPattern("yyyy.MM")

    // 0 = 현재 구간, 1 = 한 구간 이전, 2 = 두 구간 이전 ...
    private var periodOffset = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChartBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.allEntries.observe(viewLifecycleOwner) { entries ->
            refresh(entries)
        }

        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                periodOffset = 0
                updateNavVisibility(tab?.position ?: 0)
                viewModel.allEntries.value?.let { refresh(it) }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })

        binding.btnPrevPeriod.setOnClickListener {
            periodOffset++
            viewModel.allEntries.value?.let { refresh(it) }
        }

        binding.btnNextPeriod.setOnClickListener {
            if (periodOffset > 0) {
                periodOffset--
                viewModel.allEntries.value?.let { refresh(it) }
            }
        }

        updateNavVisibility(binding.tabLayout.selectedTabPosition)
    }

    private fun updateNavVisibility(tab: Int) {
        binding.llPeriodNav.visibility = if (tab == 2) View.GONE else View.VISIBLE
    }

    private fun refresh(allEntries: List<WeightEntry>) {
        val tab = binding.tabLayout.selectedTabPosition
        val (filtered, periodLabel) = computeFiltered(allEntries, tab)
        binding.tvPeriodLabel.text = periodLabel
        binding.btnNextPeriod.isEnabled = periodOffset > 0
        binding.btnNextPeriod.alpha = if (periodOffset > 0) 1f else 0.4f

        // 목표 체중도 포함해서 전체 데이터 기준 Y축 범위 계산
        val prefs = requireContext().getSharedPreferences("weight_prefs", android.content.Context.MODE_PRIVATE)
        val goal = prefs.getFloat("goal_weight", -1f).takeIf { it > 0 }
        val allWeights = allEntries.map { it.weight } + listOfNotNull(goal)
        val globalMin = allWeights.minOrNull()
        val globalMax = allWeights.maxOrNull()

        showChart(filtered, globalMin, globalMax, goal)
    }

    private fun computeFiltered(allEntries: List<WeightEntry>, tab: Int): Pair<List<WeightEntry>, String> {
        val now = LocalDate.now()
        return when (tab) {
            0 -> {
                // 1달 구간
                val end = now.minusMonths(periodOffset.toLong())
                val start = end.minusMonths(1)
                val filtered = allEntries.filter {
                    val d = LocalDate.parse(it.date, dateFmt)
                    !d.isBefore(start) && !d.isAfter(end)
                }.sortedBy { it.date }
                val label = "${start.format(monthLabelFmt)} ~ ${end.format(monthLabelFmt)}"
                Pair(filtered, label)
            }
            1 -> {
                // 1년 구간
                val end = now.minusYears(periodOffset.toLong())
                val start = end.minusYears(1)
                val filtered = allEntries.filter {
                    val d = LocalDate.parse(it.date, dateFmt)
                    !d.isBefore(start) && !d.isAfter(end)
                }.sortedBy { it.date }
                val label = "${start.format(yearLabelFmt)} ~ ${end.format(yearLabelFmt)}"
                Pair(filtered, label)
            }
            else -> {
                val filtered = allEntries.sortedBy { it.date }
                Pair(filtered, "")
            }
        }
    }

    private fun showChart(filtered: List<WeightEntry>, globalMin: Float?, globalMax: Float?, goal: Float?) {
        if (filtered.isEmpty()) {
            binding.chart.clear()
            binding.chart.setNoDataText("데이터가 없습니다.")
            binding.tvStats.text = ""
            return
        }

        val entries = filtered.mapIndexed { i, e -> Entry(i.toFloat(), e.weight) }
        val labels = filtered.map { LocalDate.parse(it.date, dateFmt).format(shortFmt) }

        val dataSet = LineDataSet(entries, "몸무게 (kg)").apply {
            color = requireContext().getColor(R.color.purple_500)
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
        }

        binding.chart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            setTouchEnabled(true)
            setPinchZoom(true)

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        val idx = value.toInt()
                        return labels.getOrElse(idx) { "" }
                    }
                }
                labelRotationAngle = -45f
                setDrawGridLines(false)
            }
            axisRight.isEnabled = false
            axisLeft.apply {
                // 전체 데이터 기준 Y축 범위 고정 (구간 이동 시에도 스케일 유지)
                if (globalMin != null && globalMax != null) {
                    val padding = ((globalMax - globalMin) * 0.1f).coerceAtLeast(2f)
                    axisMinimum = globalMin - padding
                    axisMaximum = globalMax + padding
                } else {
                    resetAxisMinimum()
                    resetAxisMaximum()
                }
                // 목표 체중 수평선
                removeAllLimitLines()
                if (goal != null) {
                    val ll = LimitLine(goal, "목표 ${"%.1f".format(goal)} kg").apply {
                        lineWidth = 1.5f
                        lineColor = Color.RED
                        enableDashedLine(10f, 8f, 0f)
                        textColor = Color.RED
                        textSize = 10f
                    }
                    addLimitLine(ll)
                }
            }
            animateX(400)
            invalidate()
        }

        val weights = filtered.map { it.weight }
        binding.tvStats.text = "최소: %.1f kg   최대: %.1f kg   평균: %.1f kg".format(
            weights.min(), weights.max(), weights.average().toFloat()
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

package com.weighttracker.app.ui.home

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.weighttracker.app.R
import com.weighttracker.app.data.model.WeightEntry
import com.weighttracker.app.databinding.FragmentHomeBinding
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels()

    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val shortFmt = DateTimeFormatter.ofPattern("yy/MM/dd")
    private val tickMmDdFmt = DateTimeFormatter.ofPattern("MM/dd")
    private val calMonthFmt = DateTimeFormatter.ofPattern("yyyy년 MM월")

    private var currentMonth = YearMonth.now()

    // 전체 그래프에서 마지막으로 터치/선택된 날짜 (하위 1달/1년 그래프의 기준일)
    private var anchorDate: String? = null
    // 월별 그래프에서 마지막으로 선택된 날짜 (마커 복원용)
    private var monthAnchorDate: String? = null

    // 전체 항목을 캐시해서 캘린더/그래프 갱신에 활용
    private var cachedEntries: List<WeightEntry> = emptyList()
    private var weightMap: Map<String, Float> = emptyMap()
    private var noteMap: Map<String, String> = emptyMap()

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            viewModel.importCsv(requireContext(), it) { count ->
                Toast.makeText(requireContext(), "${count}개 항목을 가져왔습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 캘린더는 항상 오늘 날짜가 속한 달을 기본으로 표시
        currentMonth = YearMonth.now()

        // 목표 체중 버튼
        viewModel.goalWeight.observe(viewLifecycleOwner) { goal ->
            binding.btnSetGoal.text = if (goal != null) "목표: ${"%.1f".format(goal)} kg" else "목표 설정"
            refreshChart()
        }

        // 전체 데이터 관찰
        viewModel.allEntries.observe(viewLifecycleOwner) { entries ->
            cachedEntries = entries
            weightMap = entries.associate { it.date to it.weight }
            noteMap = entries.associate { it.date to it.note }
            buildCalendar()
            refreshChart()
        }

        // 캘린더 이전/다음 월
        binding.btnPrevMonth.setOnClickListener { setCurrentMonth(currentMonth.minusMonths(1)) }
        binding.btnNextMonth.setOnClickListener { setCurrentMonth(currentMonth.plusMonths(1)) }
        binding.tvMonthLabel.setOnClickListener { showMonthYearPicker() }
        binding.btnToday.setOnClickListener { setCurrentMonth(YearMonth.now()) }
        binding.tvMonthLabel.text = currentMonth.format(calMonthFmt)

        // 체중 입력 버튼
        binding.btnSetGoal.setOnClickListener { showGoalWeightDialog() }
        binding.btnImportCsv.setOnClickListener { importLauncher.launch("*/*") }
        binding.btnExportCsv.setOnClickListener { exportCsv() }
    }

    override fun onResume() {
        super.onResume()
        // 앱을 다시 열 때(백그라운드 복귀 포함) 항상 오늘 날짜가 속한 달을 표시
        setCurrentMonth(YearMonth.now())
    }


    // ─── 캘린더 ─────────────────────────────────────────────────────────────

    private fun setCurrentMonth(month: YearMonth) {
        currentMonth = month
        binding.tvMonthLabel.text = currentMonth.format(calMonthFmt)
        buildCalendar()
    }

    private fun showMonthYearPicker() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_month_picker, null)
        val npYear = dialogView.findViewById<NumberPicker>(R.id.npYear)
        val npMonth = dialogView.findViewById<NumberPicker>(R.id.npMonth)

        val nowYear = LocalDate.now().year
        npYear.minValue = nowYear - 100
        npYear.maxValue = nowYear + 50
        npYear.value = currentMonth.year
        npYear.wrapSelectorWheel = false

        npMonth.minValue = 1
        npMonth.maxValue = 12
        npMonth.value = currentMonth.monthValue
        npMonth.wrapSelectorWheel = false

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("이동할 년월 선택")
            .setView(dialogView)
            .setPositiveButton("이동") { _, _ ->
                setCurrentMonth(YearMonth.of(npYear.value, npMonth.value))
            }
            .setNeutralButton("오늘로") { _, _ -> setCurrentMonth(YearMonth.now()) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun buildCalendar() {
        val grid = binding.glCalendar
        grid.removeAllViews()

        val today = LocalDate.now()
        val firstDay = currentMonth.atDay(1)
        // 일요일=0 기준: SUNDAY.value=7 → 7%7=0, MONDAY=1, SATURDAY=6
        val startOffset = firstDay.dayOfWeek.value % 7

        for (i in 0 until 42) {
            val date = firstDay.plusDays((i - startOffset).toLong())
            val inMonth = date.month == currentMonth.month && date.year == currentMonth.year
            val weight = weightMap[date.format(dateFmt)]
            val isToday = date == today

            val cell = buildCell(date, weight, inMonth, isToday)
            // 그리드가 차지하는 높이/너비를 6행 7열에 균등하게(weight) 분배
            val colSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            val rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            cell.layoutParams = GridLayout.LayoutParams(rowSpec, colSpec).apply {
                width = 0
                height = 0
                setMargins(0, 0, 0, 0)
            }
            grid.addView(cell)
        }
    }

    private fun buildCell(date: LocalDate, weight: Float?, inMonth: Boolean, isToday: Boolean): View {
        val ctx = requireContext()
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            if (isToday) setBackgroundResource(R.drawable.bg_calendar_today)
            setOnClickListener { showWeightInputDialog(date.format(dateFmt)) }
        }

        // 일 번호
        val tvDay = TextView(ctx).apply {
            text = date.dayOfMonth.toString()
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            val dow = date.dayOfWeek.value % 7  // 0=일, 6=토
            when {
                isToday -> {
                    setTextColor(ctx.getColor(R.color.purple_500))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                !inMonth -> setTextColor(Color.LTGRAY)
                dow == 0 -> setTextColor(Color.parseColor("#E53935"))
                dow == 6 -> setTextColor(Color.parseColor("#1565C0"))
                else -> setTextColor(Color.BLACK)
            }
        }

        // 체중 (기록 있을 때만)
        val tvWeight = TextView(ctx).apply {
            text = weight?.let { "%.1f".format(it) } ?: ""
            textSize = 10f
            gravity = android.view.Gravity.CENTER
            setTextColor(ctx.getColor(R.color.purple_500))
            if (!inMonth) alpha = 0.5f
        }

        cell.addView(tvDay)
        cell.addView(tvWeight)
        return cell
    }

    // ─── 그래프 ─────────────────────────────────────────────────────────────

    private fun refreshChart() {
        val sorted = cachedEntries.sortedBy { it.date }

        val prefs = requireContext().getSharedPreferences("weight_prefs", Context.MODE_PRIVATE)
        val goal = prefs.getFloat("goal_weight", -1f).takeIf { it > 0 }
        val allWeights = sorted.map { it.weight } + listOfNotNull(goal)
        val globalMin = allWeights.minOrNull()
        val globalMax = allWeights.maxOrNull()

        // 기준일: 마지막으로 터치한 날짜, 없으면 최신 기록일
        val anchor = anchorDate ?: sorted.lastOrNull()?.date

        // 전체 그래프 (X축 확대/축소 가능) - 기준일에 마커 복원
        drawChart(binding.chartAll, sorted, globalMin, globalMax, goal, labelCount = 5, zoomableX = true, highlightDate = anchor)
        setupAllChartTouch(sorted, globalMin, globalMax, goal)

        updateYearChart(anchor, sorted, globalMin, globalMax, goal)
    }

    /** 전체 그래프 터치 → 해당 연도 데이터를 년별 그래프에 표시 */
    private fun setupAllChartTouch(
        sorted: List<WeightEntry>,
        globalMin: Float?,
        globalMax: Float?,
        goal: Float?
    ) {
        binding.chartAll.isHighlightPerTapEnabled = true
        // 드래그는 팬(좌우 이동)에 사용하므로 드래그 하이라이트는 끔
        binding.chartAll.isHighlightPerDragEnabled = false
        binding.chartAll.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                // x = epoch day → 날짜로 복원
                val epochDay = e?.x?.let { Math.round(it).toLong() } ?: return
                val date = LocalDate.ofEpochDay(epochDay).format(dateFmt)
                anchorDate = date
                updateYearChart(date, sorted, globalMin, globalMax, goal)
            }
            override fun onNothingSelected() {}
        })
    }

    /** anchor가 속한 연도(1/1~12/31)의 데이터를 년별 그래프에 그리고, anchor가 속한 달로 월별 그래프 갱신 */
    private fun updateYearChart(
        anchor: String?,
        sorted: List<WeightEntry>,
        globalMin: Float?,
        globalMax: Float?,
        goal: Float?
    ) {
        if (anchor == null) {
            binding.chartMonth.clear()
            binding.chartMonth.setNoDataText("데이터가 없습니다.")
            binding.chartYear.clear()
            binding.chartYear.setNoDataText("데이터가 없습니다.")
            binding.tvMonthChartLabel.text = "월별"
            binding.tvYearChartLabel.text = "년별"
            return
        }

        val anchorDay = LocalDate.parse(anchor, dateFmt)
        val year = anchorDay.year

        val yearFiltered = sorted.filter {
            LocalDate.parse(it.date, dateFmt).year == year
        }
        binding.tvYearChartLabel.text = "년별 (${year}년, 터치하면 월별 갱신)"
        // 기준일에 마커 복원, 목표값 중심 Y축
        drawChart(binding.chartYear, yearFiltered, globalMin, globalMax, goal, labelCount = 3, tickFmt = tickMmDdFmt, highlightDate = anchor, centerOnGoal = true)

        // 년별 그래프 터치 → 해당 월의 데이터를 월별 그래프에 표시
        binding.chartYear.isHighlightPerTapEnabled = true
        binding.chartYear.isHighlightPerDragEnabled = true
        binding.chartYear.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                val epochDay = e?.x?.let { Math.round(it).toLong() } ?: return
                val touched = LocalDate.ofEpochDay(epochDay)
                monthAnchorDate = touched.format(dateFmt)
                updateMonthChart(YearMonth.from(touched), sorted, globalMin, globalMax, goal)
            }
            override fun onNothingSelected() {}
        })

        // 기본 월: anchor가 속한 달, 마커는 기준일에 복원
        monthAnchorDate = anchor
        updateMonthChart(YearMonth.from(anchorDay), sorted, globalMin, globalMax, goal)
    }

    /** 지정한 연월(1일~말일)의 데이터를 월별 그래프에 표시 */
    private fun updateMonthChart(
        month: YearMonth,
        sorted: List<WeightEntry>,
        globalMin: Float?,
        globalMax: Float?,
        goal: Float?
    ) {
        val monthFiltered = sorted.filter {
            YearMonth.from(LocalDate.parse(it.date, dateFmt)) == month
        }
        binding.tvMonthChartLabel.text = "월별 (${month.year}.${"%02d".format(month.monthValue)})"
        // 마지막 선택 날짜에 마커 복원, 목표값 중심 Y축
        drawChart(binding.chartMonth, monthFiltered, globalMin, globalMax, goal, labelCount = 3, tickFmt = tickMmDdFmt, highlightDate = monthAnchorDate, centerOnGoal = true)
    }

    private fun drawChart(
        chart: LineChart,
        filtered: List<WeightEntry>,
        globalMin: Float?,
        globalMax: Float?,
        goal: Float?,
        labelCount: Int,
        tickFmt: DateTimeFormatter = shortFmt,
        zoomableX: Boolean = false,
        highlightDate: String? = null,
        centerOnGoal: Boolean = false
    ) {
        if (filtered.isEmpty()) {
            chart.clear()
            chart.setNoDataText("데이터가 없습니다.")
            return
        }

        // X축을 실제 날짜(epoch day) 기준으로 사용 → 모든 일자가 시간 비례로 표시됨
        val entries = filtered.map { e ->
            Entry(LocalDate.parse(e.date, dateFmt).toEpochDay().toFloat(), e.weight)
        }

        val dataSet = LineDataSet(entries, "몸무게 (kg)").apply {
            color = requireContext().getColor(R.color.purple_500)
            lineWidth = 2f
            // 포인트가 적으면 선이 거의 안 보이므로 원 마커를 켜서 표시
            setDrawCircles(entries.size <= 3)
            circleRadius = 3.5f
            setCircleColor(requireContext().getColor(R.color.purple_500))
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
        }

        chart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            setTouchEnabled(true)
            if (zoomableX) {
                // X축 방향으로만 핀치 확대/축소 및 좌우 이동 허용
                setPinchZoom(true)
                setScaleXEnabled(true)
                setScaleYEnabled(false)
                isDragEnabled = true
                isDoubleTapToZoomEnabled = true
            } else {
                // 팬/줌을 모두 꺼서 항상 전체 구간이 보이도록 함
                // (터치는 하이라이트 선택 용도로만 사용)
                setPinchZoom(false)
                setScaleEnabled(false)
                isDragEnabled = false
            }
            // 이전 줌/이동 상태 초기화
            fitScreen()

            // 터치 지점의 날짜/몸무게 마커 (모든 그래프 공통)
            isHighlightPerTapEnabled = true
            marker = DateMarkerView(requireContext()).apply { chartView = chart }

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
                // 축 범위를 첫/마지막 기록일에 정확히 맞춰서
                // force 라벨의 처음과 끝 눈금이 실제 데이터 날짜에 찍히게 함
                axisMinimum = entries.first().x
                axisMaximum = entries.last().x
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String =
                        LocalDate.ofEpochDay(Math.round(value).toLong()).format(tickFmt)
                }
                labelRotationAngle = 0f
                // force=true: 처음과 끝(axisMin/axisMax)에 항상 눈금 표시,
                // 나머지는 균등 분배
                setLabelCount(labelCount, true)
                // 처음/끝 라벨이 차트 경계에서 잘리지 않도록 안쪽으로 당겨서 표시
                setAvoidFirstLastClipping(true)
                setDrawGridLines(false)
            }
            axisRight.isEnabled = false
            axisLeft.apply {
                if (centerOnGoal && goal != null) {
                    // 목표값을 Y축 중앙에 두고, 이 구간 데이터의 목표 대비 최대 편차로 대칭 범위 설정
                    val maxDev = entries.map { kotlin.math.abs(it.y - goal) }.maxOrNull() ?: 0f
                    val half = (maxDev * 1.1f).coerceAtLeast(2f)
                    axisMinimum = goal - half
                    axisMaximum = goal + half
                } else if (globalMin != null && globalMax != null) {
                    val pad = ((globalMax - globalMin) * 0.1f).coerceAtLeast(2f)
                    axisMinimum = globalMin - pad
                    axisMaximum = globalMax + pad
                } else {
                    resetAxisMinimum()
                    resetAxisMaximum()
                }
                removeAllLimitLines()
                if (goal != null) {
                    addLimitLine(LimitLine(goal, "목표 ${"%.1f".format(goal)} kg").apply {
                        lineWidth = 1.5f
                        lineColor = Color.RED
                        enableDashedLine(10f, 8f, 0f)
                        textColor = Color.RED
                        textSize = 9f
                    })
                }
            }
            animateX(300)
            invalidate()

            // 다시 그린 뒤 마지막 선택 지점에 하이라이트를 재적용해 마커 복원
            // (가장 가까운 데이터 포인트에 스냅, 리스너는 호출하지 않음)
            if (highlightDate != null) {
                val targetX = LocalDate.parse(highlightDate, dateFmt).toEpochDay().toFloat()
                val nearest = entries.minByOrNull { kotlin.math.abs(it.x - targetX) }
                if (nearest != null) {
                    highlightValue(nearest.x, 0, false)
                }
            }
        }
    }

    // ─── 다이얼로그 ──────────────────────────────────────────────────────────

    private fun showGoalWeightDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_weight_input, null)
        val etWeight = dialogView.findViewById<TextInputEditText>(R.id.etWeight)
        val etNote = dialogView.findViewById<TextInputEditText>(R.id.etNote)
        etNote.visibility = View.GONE
        viewModel.goalWeight.value?.let { etWeight.setText("%.1f".format(it)) }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("목표 체중 설정 (kg)")
            .setView(dialogView)
            .setPositiveButton("저장") { _, _ ->
                val w = etWeight.text.toString().toFloatOrNull()
                if (w == null || w <= 0) {
                    Toast.makeText(requireContext(), "올바른 목표 체중을 입력하세요.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                viewModel.setGoalWeight(w)
            }
            .setNeutralButton("목표 제거") { _, _ -> viewModel.setGoalWeight(null) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showWeightInputDialog(date: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_weight_input, null)
        val etWeight = dialogView.findViewById<TextInputEditText>(R.id.etWeight)
        val etNote = dialogView.findViewById<TextInputEditText>(R.id.etNote)

        // 기존 데이터 미리 채우기
        weightMap[date]?.let { etWeight.setText("%.1f".format(it)) }
        noteMap[date]?.takeIf { it.isNotEmpty() }?.let { etNote.setText(it) }

        val title = if (weightMap.containsKey(date)) "몸무게 수정 ($date)" else "몸무게 입력 ($date)"

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton("저장") { _, _ ->
                val w = etWeight.text.toString().toFloatOrNull()
                if (w == null || w <= 0) {
                    Toast.makeText(requireContext(), "올바른 몸무게를 입력하세요.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                viewModel.saveWeight(date, w, etNote.text.toString())
                Toast.makeText(requireContext(), "저장되었습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun exportCsv() {
        viewModel.exportCsv(requireContext()) { file ->
            if (file != null) {
                val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "CSV 내보내기"))
            } else {
                Toast.makeText(requireContext(), "내보내기 실패", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

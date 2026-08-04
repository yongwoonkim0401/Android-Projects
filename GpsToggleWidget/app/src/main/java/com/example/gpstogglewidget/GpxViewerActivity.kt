package com.example.gpstogglewidget

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Environment
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebView
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.Locale

/**
 * 저장된 GPX 파일 목록을 보여주고, 선택한 파일의 경로를
 * 지도(OpenStreetMap) 위에 그려서 표시하는 화면.
 * 위젯 더블클릭으로 열린다.
 *
 * - 파일 탭: 지도에 경로 표시
 * - 파일 길게 누름: 선택 모드 진입 (복수 선택) → 삭제
 */
class GpxViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        private const val MENU_RENAME = 1
        private const val MENU_DELETE = 2
    }

    private lateinit var listView: ListView
    private lateinit var webView: WebView
    private var files: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_gpx_viewer)
        listView = findViewById(R.id.lv_gpx_files)
        webView = findViewById(R.id.wv_map)

        // 특정 파일 경로가 넘어오면 목록 없이 바로 지도 표시 (폴더 화면에서 진입)
        val directPath = intent.getStringExtra(EXTRA_FILE_PATH)
        if (directPath != null) {
            showRoute(File(directPath))
            return
        }

        setupMultiChoiceDelete()
        loadFileList(finishIfEmpty = true)

        listView.setOnItemClickListener { _, _, position, _ ->
            showRoute(files[position])
        }
    }

    /** 파일 목록을 읽어 리스트에 표시한다. */
    private fun loadFileList(finishIfEmpty: Boolean) {
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        files = dir.listFiles { f -> f.name.endsWith(".gpx") }
            ?.sortedByDescending { it.name }
            ?: emptyList()

        if (files.isEmpty()) {
            Toast.makeText(this, "저장된 GPX 파일이 없습니다", Toast.LENGTH_SHORT).show()
            if (finishIfEmpty) finish()
            return
        }

        listView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_activated_1,
            files.map { it.name }
        )
    }

    /** 길게 누르면 선택 모드 진입 → 복수 선택 → 삭제. */
    private fun setupMultiChoiceDelete() {
        listView.choiceMode = AbsListView.CHOICE_MODE_MULTIPLE_MODAL
        listView.setMultiChoiceModeListener(object : AbsListView.MultiChoiceModeListener {

            override fun onItemCheckedStateChanged(
                mode: ActionMode,
                position: Int,
                id: Long,
                checked: Boolean
            ) {
                mode.title = "${listView.checkedItemCount}개 선택됨"
            }

            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, MENU_RENAME, 0, "이름 변경")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add(0, MENU_DELETE, 1, "삭제")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                mode.title = "1개 선택됨"
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                return when (item.itemId) {
                    MENU_RENAME -> { renameSelected(mode); true }
                    MENU_DELETE -> { confirmAndDelete(mode); true }
                    else -> false
                }
            }

            override fun onDestroyActionMode(mode: ActionMode) {
            }
        })
    }

    /** 선택된 파일(정확히 1개)의 이름을 변경한다. */
    private fun renameSelected(mode: ActionMode) {
        val checked = listView.checkedItemPositions
        val targets = files.filterIndexed { index, _ -> checked.get(index) }
        if (targets.size != 1) {
            Toast.makeText(this, "이름을 변경할 파일 1개만 선택해 주세요", Toast.LENGTH_SHORT).show()
            return
        }
        val target = targets[0]
        val baseName = target.name.removeSuffix(".gpx")

        val input = EditText(this).apply {
            setText(baseName)
            setSelection(text.length)
            setSingleLine(true)
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("이름 변경")
            .setMessage("새 파일 이름을 입력하세요 (.gpx는 자동으로 붙습니다)")
            .setView(container)
            .setPositiveButton("변경") { _, _ ->
                val newBase = input.text.toString().trim()
                    .replace(Regex("""[/\\:*?"<>|]"""), "_")  // 파일명 불가 문자 치환
                if (newBase.isEmpty()) {
                    Toast.makeText(this, "이름이 비어 있습니다", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val dest = File(target.parentFile, "$newBase.gpx")
                when {
                    dest.name == target.name -> mode.finish()
                    dest.exists() ->
                        Toast.makeText(this, "같은 이름의 파일이 이미 있습니다", Toast.LENGTH_SHORT).show()
                    target.renameTo(dest) -> {
                        Toast.makeText(this, "이름을 변경했습니다", Toast.LENGTH_SHORT).show()
                        mode.finish()
                        loadFileList(finishIfEmpty = false)
                    }
                    else ->
                        Toast.makeText(this, "이름 변경에 실패했습니다", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 선택된 파일들을 확인 후 삭제한다. */
    private fun confirmAndDelete(mode: ActionMode) {
        val checked = listView.checkedItemPositions
        val targets = files.filterIndexed { index, _ -> checked.get(index) }
        if (targets.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle("파일 삭제")
            .setMessage("선택한 ${targets.size}개 파일을 삭제할까요?\n\n" +
                    targets.joinToString("\n") { it.name })
            .setPositiveButton("삭제") { _, _ ->
                var deleted = 0
                targets.forEach { if (it.delete()) deleted++ }
                Toast.makeText(this, "${deleted}개 파일을 삭제했습니다", Toast.LENGTH_SHORT).show()
                mode.finish()
                loadFileList(finishIfEmpty = false)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onBackPressed() {
        // 지도 화면에서 뒤로가기 → 파일 목록으로 복귀 (직접 열기 모드면 종료)
        if (webView.visibility == android.view.View.VISIBLE &&
            intent.getStringExtra(EXTRA_FILE_PATH) == null
        ) {
            webView.visibility = android.view.View.GONE
            listView.visibility = android.view.View.VISIBLE
        } else {
            super.onBackPressed()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showRoute(file: File) {
        val points = parseTrackPoints(file)
        if (points.isEmpty()) {
            Toast.makeText(this, "이 파일에는 경로 데이터가 없습니다", Toast.LENGTH_SHORT).show()
            return
        }

        listView.visibility = android.view.View.GONE
        webView.visibility = android.view.View.VISIBLE
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        // [lat, lon, ele(없으면 null), timeMillis(없으면 null)]
        val jsPoints = points.joinToString(",") { p ->
            val ele = p.ele?.let { String.format(Locale.US, "%.1f", it) } ?: "null"
            val time = p.timeMillis?.toString() ?: "null"
            String.format(Locale.US, "[%.6f,%.6f,%s,%s]", p.lat, p.lon, ele, time)
        }

        // 선택된 지도 (키가 없는 네이버/카카오는 OSM으로 폴백)
        var provider = GpsToggleWidget.getMapProvider(this)
        if (provider == "naver" && MapKeys.NAVER_CLIENT_ID.isBlank()) provider = "osm"
        if (provider == "kakao" && MapKeys.KAKAO_JS_KEY.isBlank()) provider = "osm"

        // 네이버/카카오는 공식 SDK를 Leaflet 타일 레이어로 연결해 사용한다
        val mapSdkScript = when (provider) {
            "naver" ->
                // 최신 인증 파라미터는 ncpKeyId (구 ncpClientId에서 변경됨)
                """<script src="https://oapi.map.naver.com/openapi/v3/maps.js?ncpKeyId=${MapKeys.NAVER_CLIENT_ID}"></script>"""
            "kakao" ->
                // autoload=false + kakao.maps.load(cb) 로 로드 완료 후 초기화해야 한다
                """<script src="https://dapi.kakao.com/v2/maps/sdk.js?appkey=${MapKeys.KAKAO_JS_KEY}&autoload=false"></script>"""
            else -> ""
        }

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
              <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
              $mapSdkScript
              <style>
                html,body{margin:0;padding:0;width:100%;height:100%}
                #map{position:absolute; top:0; left:0; right:0; bottom:200px}
                #chart{
                  position:absolute; left:0; right:0; bottom:0; height:200px;
                  background:#fff; border-top:1px solid #CCC;
                }
                /* 그래프 숨김(전체화면) 상태 */
                body.nochart #map{bottom:0}
                body.nochart #chart{display:none}
                #btn_chart{
                  position:absolute; bottom:210px; right:10px; z-index:1000;
                  background:rgba(255,255,255,0.92); border:1px solid #BBB;
                  border-radius:8px; width:40px; height:34px; padding:0;
                  font:16px/1 sans-serif; color:#333;
                  box-shadow:0 1px 4px rgba(0,0,0,0.3); cursor:pointer;
                }
                body.nochart #btn_chart{bottom:10px}
                /* 순수 곡선 영역 동일화: 고도(위 10 + 아래 6 여백) vs 속도(위 10 + X축 32)
                   87-16 = 113-42 = 71px 로 두 그래프의 곡선 영역이 같다 */
                #cv{width:100%; height:87px; display:block}
                #cvs{width:100%; height:113px; display:block; border-top:1px solid #EEE}
                #stats{
                  position:absolute; top:10px; right:10px; z-index:1000;
                  background:rgba(255,255,255,0.92); border-radius:8px;
                  padding:8px 12px; font:12px/1.6 sans-serif;
                  box-shadow:0 1px 4px rgba(0,0,0,0.3);
                }
                #stats b{display:inline-block; min-width:70px}
              </style>
            </head>
            <body>
              <div id="map"></div>
              <div id="stats"></div>
              <button id="btn_chart" type="button" title="그래프 숨기기">&#9660;</button>
              <div id="chart"><canvas id="cv"></canvas><canvas id="cvs"></canvas></div>
              <script>
                var pts = [$jsPoints];
                var latlngs = pts.map(function(p){ return [p[0], p[1]]; });

                var PROVIDER = '$provider';

                // 거리 계산은 지도 SDK와 무관하게 자체 계산 (Haversine)
                function distOf(a, b) {
                  var R = 6371008.8, rad = Math.PI/180;
                  var dLat = (b[0]-a[0])*rad, dLon = (b[1]-a[1])*rad;
                  var s = Math.sin(dLat/2)*Math.sin(dLat/2) +
                          Math.cos(a[0]*rad)*Math.cos(b[0]*rad)*Math.sin(dLon/2)*Math.sin(dLon/2);
                  return 2*R*Math.asin(Math.min(1, Math.sqrt(s)));
                }

                // ───── 누적 거리 (통계 + 그래프 x축 공용) ─────
                var cumDist = [0];
                for (var i = 1; i < latlngs.length; i++) {
                  cumDist.push(cumDist[i-1] + distOf(latlngs[i-1], latlngs[i]));
                }
                var dist = cumDist[cumDist.length-1];

                /* ───── 지도 어댑터: OSM(Leaflet) / 네이버 / 카카오 공통 인터페이스 ─────
                   drawRoute / setSelected / panTo / onMapClick 를 제공한다 */
                function buildNaver(){
                  {
                    /* 네이버 지도는 컨테이너 크기를 명시해야 내부 레이어 높이가 잡힌다.
                       top/bottom 절대배치만으로는 높이를 0으로 인식해 흰 화면이 된다.
                       단, 첫 표시 때는 레이아웃 전이라 clientHeight가 0일 수 있으므로
                       화면 크기로 보정하고, 레이아웃이 끝난 뒤 다시 맞춘다. */
                    var CHART_H = 200;
                    var el = document.getElementById('map');

                    function measure() {
                      // 인라인 크기를 비워 CSS(top/bottom) 기준 실제 크기를 다시 읽는다
                      el.style.width = ''; el.style.height = '';
                      var winW = window.innerWidth || document.documentElement.clientWidth;
                      var winH = window.innerHeight || document.documentElement.clientHeight;
                      // 그래프가 숨겨져 있으면 지도가 화면 전체를 쓴다
                      var reserved = document.body.classList.contains('nochart') ? 0 : CHART_H;
                      var w = el.clientWidth || winW;
                      var h = el.clientHeight || (winH - reserved);
                      if (w < 1) w = 320;
                      if (h < 1) h = 320;
                      el.style.width = w + 'px';
                      el.style.height = h + 'px';
                      return {w: w, h: h};
                    }

                    var sz0 = measure();
                    var m = new naver.maps.Map(el, {
                      zoom: 14,
                      center: new naver.maps.LatLng(latlngs[0][0], latlngs[0][1]),
                      size: new naver.maps.Size(sz0.w, sz0.h),
                      // 하이브리드: 위성 영상 + 도로/지명 라벨
                      mapTypeId: naver.maps.MapTypeId.HYBRID
                    });

                    var routeBounds = null;
                    function refit() {
                      var s = measure();
                      m.setSize(new naver.maps.Size(s.w, s.h));
                      naver.maps.Event.trigger(m, 'resize');
                      if (routeBounds) m.fitBounds(routeBounds);
                    }
                    // 레이아웃이 늦게 잡히는 경우(첫 표시)를 대비해 몇 차례 재보정
                    setTimeout(refit, 60);
                    setTimeout(refit, 250);
                    setTimeout(refit, 700);
                    window.addEventListener('resize', refit);
                    window.addEventListener('load', refit);
                    var iw = new naver.maps.InfoWindow({content:''});
                    var sel = null;
                    return {
                      drawRoute: function(){
                        new naver.maps.Polyline({
                          map: m, strokeColor:'#E5322D', strokeWeight:2,
                          path: latlngs.map(function(p){ return new naver.maps.LatLng(p[0], p[1]); })
                        });
                        var b = new naver.maps.LatLngBounds(
                          new naver.maps.LatLng(latlngs[0][0], latlngs[0][1]),
                          new naver.maps.LatLng(latlngs[0][0], latlngs[0][1]));
                        latlngs.forEach(function(p){ b.extend(new naver.maps.LatLng(p[0], p[1])); });
                        routeBounds = b;
                        m.fitBounds(b);
                        function mk(p, emoji, title){
                          new naver.maps.Marker({
                            map: m, position: new naver.maps.LatLng(p[0], p[1]), title: title,
                            icon: {content:'<div style="font-size:30px;line-height:30px">'+emoji+'</div>', anchor: new naver.maps.Point(4, 28)}
                          });
                        }
                        mk(latlngs[0], '&#128681;', '출발점');
                        mk(latlngs[latlngs.length-1], '&#127937;', '도착점');
                      },
                      setSelected: function(p, html){
                        var pos = new naver.maps.LatLng(p[0], p[1]);
                        if (!sel) sel = new naver.maps.Marker({map:m, position:pos});
                        else sel.setPosition(pos);
                        iw.setContent('<div style="padding:6px 10px;font:12px sans-serif">'+html+'</div>');
                        iw.open(m, sel);
                      },
                      panTo: function(p){ m.panTo(new naver.maps.LatLng(p[0], p[1])); },
                      // 레이아웃 반영 직후와 잠시 뒤 두 번 맞춘다 (전환 타이밍 대응)
                      resize: function(){
                        refit();
                        setTimeout(refit, 0);
                        setTimeout(refit, 150);
                      },
                      onMapClick: function(cb){
                        naver.maps.Event.addListener(m, 'click', function(e){
                          cb([e.coord.lat(), e.coord.lng()]);
                        });
                      }
                    };
                  }
                }
                function buildKakao(){
                  {
                    var km = new kakao.maps.Map(document.getElementById('map'), {
                      center: new kakao.maps.LatLng(latlngs[0][0], latlngs[0][1]), level: 5
                    });
                    /* 하이브리드: 위성(SKYVIEW) + 도로/지명 라벨(OVERLAY) 겹치기
                       카카오에서 위성 위 라벨 레이어는 HYBRID가 아니라 OVERLAY 타입이다 */
                    km.setMapTypeId(kakao.maps.MapTypeId.SKYVIEW);
                    km.addOverlayMapTypeId(kakao.maps.MapTypeId.OVERLAY);
                    var kiw = new kakao.maps.InfoWindow({removable:false});
                    var ksel = null;
                    var kbounds = null;
                    return {
                      drawRoute: function(){
                        new kakao.maps.Polyline({
                          map: km, strokeColor:'#E5322D', strokeWeight:2, strokeOpacity:1,
                          path: latlngs.map(function(p){ return new kakao.maps.LatLng(p[0], p[1]); })
                        });
                        var kb = new kakao.maps.LatLngBounds();
                        latlngs.forEach(function(p){ kb.extend(new kakao.maps.LatLng(p[0], p[1])); });
                        kbounds = kb;
                        km.setBounds(kb);
                        function kmk(p, emoji, title){
                          var ov = new kakao.maps.CustomOverlay({
                            map: km, position: new kakao.maps.LatLng(p[0], p[1]), yAnchor: 1,
                            content: '<div title="'+title+'" style="font-size:30px;line-height:30px">'+emoji+'</div>'
                          });
                          return ov;
                        }
                        kmk(latlngs[0], '&#128681;', '출발점');
                        kmk(latlngs[latlngs.length-1], '&#127937;', '도착점');
                      },
                      setSelected: function(p, html){
                        var pos = new kakao.maps.LatLng(p[0], p[1]);
                        if (!ksel) ksel = new kakao.maps.Marker({map:km, position:pos});
                        else ksel.setPosition(pos);
                        kiw.setContent('<div style="padding:6px 10px;font:12px sans-serif">'+html+'</div>');
                        kiw.setPosition(pos);
                        kiw.open(km, ksel);
                      },
                      panTo: function(p){ km.panTo(new kakao.maps.LatLng(p[0], p[1])); },
                      resize: function(){
                        km.relayout();
                        if (kbounds) km.setBounds(kbounds);
                      },
                      onMapClick: function(cb){
                        kakao.maps.event.addListener(km, 'click', function(e){
                          cb([e.latLng.getLat(), e.latLng.getLng()]);
                        });
                      }
                    };
                  }
                }
                // 기본: OpenStreetMap (Leaflet)
                function buildOsm(){
                  var lm = L.map('map');
                  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
                    maxZoom: 19, attribution: '&copy; OpenStreetMap contributors'
                  }).addTo(lm);
                  var lsel = null;
                  var lbounds = null;
                  return {
                    drawRoute: function(){
                      var line = L.polyline(latlngs, {color:'#E5322D', weight:2}).addTo(lm);
                      lbounds = line.getBounds();
                      lm.fitBounds(lbounds, {padding:[30,30]});
                      function icon(emoji){
                        return L.divIcon({className:'',
                          html:'<div style="font-size:30px; line-height:30px;">'+emoji+'</div>',
                          iconSize:[30,30], iconAnchor:[4,28]});
                      }
                      L.marker(latlngs[0], {icon:icon('&#128681;')}).addTo(lm).bindPopup('출발점');
                      L.marker(latlngs[latlngs.length-1], {icon:icon('&#127937;')}).addTo(lm).bindPopup('도착점');
                    },
                    setSelected: function(p, html){
                      if (!lsel) lsel = L.marker(p).addTo(lm);
                      else lsel.setLatLng(p);
                      lsel.bindPopup(html).openPopup();
                    },
                    panTo: function(p){ lm.panTo(p); },
                    resize: function(){
                      lm.invalidateSize();
                      if (lbounds) lm.fitBounds(lbounds, {padding:[30,30]});
                    },
                    onMapClick: function(cb){
                      lm.on('click', function(e){ cb([e.latlng.lat, e.latlng.lng]); });
                    }
                  };
                }

                /* ───── 지도 초기화 (실패 시 OSM으로 폴백) ─────
                   카카오는 SDK 로드 완료(kakao.maps.load) 후 초기화해야 한다 */
                var MapView = null;
                var mapClickCb = null;

                function useMap(builder) {
                  try {
                    MapView = builder();
                    MapView.drawRoute();
                    if (mapClickCb) MapView.onMapClick(mapClickCb);
                    return true;
                  } catch (err) {
                    return false;
                  }
                }
                // 지도 SDK 사용 불가 시 OpenStreetMap으로 대체
                function fallbackToOsm() {
                  var el = document.getElementById('map');
                  el.innerHTML = '';
                  el.style.width = ''; el.style.height = '';
                  useMap(buildOsm);
                }

                // 네이버 인증 실패 감지 (v3 전역 콜백)
                window.navermap_authFailure = fallbackToOsm;

                if (PROVIDER === 'naver') {
                  if (window.naver && naver.maps && naver.maps.Map) {
                    if (!useMap(buildNaver)) fallbackToOsm();
                  } else {
                    fallbackToOsm();
                  }
                } else if (PROVIDER === 'kakao') {
                  if (window.kakao && kakao.maps && kakao.maps.load) {
                    kakao.maps.load(function(){
                      if (!useMap(buildKakao)) fallbackToOsm();
                    });
                    // 로드가 끝나지 않으면(키/도메인 미등록 등) 일정 시간 후 폴백
                    setTimeout(function(){
                      if (MapView === null) fallbackToOsm();
                    }, 4000);
                  } else {
                    fallbackToOsm();
                  }
                } else {
                  useMap(buildOsm);
                }

                // 걸린 시간 (첫/마지막 시각)
                var times = pts.map(function(p){return p[3];}).filter(function(t){return t !== null;});
                var durText = '-', avgSpdText = '-', startText = '-', endText = '-';
                if (times.length >= 2) {
                  var sec = Math.round((times[times.length-1] - times[0]) / 1000);
                  var h = Math.floor(sec/3600), m = Math.floor((sec%3600)/60), s = sec%60;
                  durText = (h>0 ? h+'시간 ' : '') + m + '분 ' + s + '초';
                  if (sec > 0) {
                    avgSpdText = (dist / sec * 3.6).toFixed(1) + ' km/h';
                  }
                  startText = fmtFullTime(times[0]);
                  endText = fmtFullTime(times[times.length-1]);
                }

                // 년도 포함 24시간 형식: 2026-07-12 09:30:15
                function fmtFullTime(ms) {
                  var t = new Date(ms);
                  function z(n){ return ('0'+n).slice(-2); }
                  return t.getFullYear() + '-' + z(t.getMonth()+1) + '-' + z(t.getDate()) +
                         ' ' + z(t.getHours()) + ':' + z(t.getMinutes()) + ':' + z(t.getSeconds());
                }

                // 최고 속도 (구간 속도, 3점 이동평균으로 노이즈 완화 후 최댓값)
                var maxSpdText = '-';
                (function(){
                  var spd = [], lastI = -1;
                  for (var i = 0; i < pts.length; i++) {
                    if (pts[i][3] === null) continue;
                    if (lastI >= 0) {
                      var dt = (pts[i][3] - pts[lastI][3]) / 1000;
                      if (dt > 0) spd.push((cumDist[i] - cumDist[lastI]) / dt * 3.6);
                    }
                    lastI = i;
                  }
                  if (spd.length === 0) return;
                  var maxS = 0;
                  for (var i = 0; i < spd.length; i++) {
                    var a = Math.max(0, i-1), b = Math.min(spd.length-1, i+1), s = 0;
                    for (var j = a; j <= b; j++) s += spd[j];
                    var v = s / (b - a + 1);
                    if (v > maxS) maxS = v;
                  }
                  maxSpdText = maxS.toFixed(1) + ' km/h';
                })();

                // 누적 오르막/내리막 (GPS 고도 노이즈 완화: 3m 이상 변화만 반영)
                var ascent = 0, descent = 0, lastEle = null;
                var THRESHOLD = 3.0;
                pts.forEach(function(p){
                  var e = p[2];
                  if (e === null) return;
                  if (lastEle === null) { lastEle = e; return; }
                  var diff = e - lastEle;
                  if (Math.abs(diff) >= THRESHOLD) {
                    if (diff > 0) ascent += diff; else descent += -diff;
                    lastEle = e;
                  }
                });

                // 최고 높이 (고도값이 있는 포인트 중 최댓값)
                var eles = pts.map(function(p){return p[2];}).filter(function(e){return e !== null;});
                var maxEleText = eles.length > 0
                  ? Math.round(Math.max.apply(null, eles)) + ' m'
                  : '-';

                var distText = dist >= 1000
                  ? (dist/1000).toFixed(2) + ' km'
                  : Math.round(dist) + ' m';
                document.getElementById('stats').innerHTML =
                  '<b>출발 시간</b> ' + startText + '<br>' +
                  '<b>도착 시간</b> ' + endText + '<br>' +
                  '<b>총 거리</b> ' + distText + '<br>' +
                  '<b>걸린 시간</b> ' + durText + '<br>' +
                  '<b>평균 속도</b> ' + avgSpdText + '<br>' +
                  '<b>최고 속도</b> ' + maxSpdText + '<br>' +
                  '<b>최고 고도</b> ' + maxEleText + '<br>' +
                  '<b>누적 오르막</b> +' + Math.round(ascent) + ' m<br>' +
                  '<b>누적 내리막</b> -' + Math.round(descent) + ' m';

                // ───── 고도 그래프 (하단) ─────
                // 고도가 있는 포인트만 프로파일로 사용
                var profIdx = [], profDist = [], profEle = [];
                pts.forEach(function(p, i){
                  if (p[2] !== null) {
                    profIdx.push(i); profDist.push(cumDist[i]); profEle.push(p[2]);
                  }
                });

                // 속도 프로파일 (구간 거리/구간 시간 → km/h, 3점 이동평균)
                var spdDist = [], spdVal = [];
                (function(){
                  var raw = [], lastI = -1;
                  for (var i = 0; i < pts.length; i++) {
                    if (pts[i][3] === null) continue;
                    if (lastI >= 0) {
                      var dt = (pts[i][3] - pts[lastI][3]) / 1000;
                      if (dt > 0) {
                        spdDist.push(cumDist[i]);
                        raw.push((cumDist[i] - cumDist[lastI]) / dt * 3.6);
                      }
                    }
                    lastI = i;
                  }
                  for (var i = 0; i < raw.length; i++) {
                    var a = Math.max(0, i-1), b = Math.min(raw.length-1, i+1), s = 0;
                    for (var j = a; j <= b; j++) s += raw[j];
                    spdVal.push(s / (b - a + 1));
                  }
                })();

                var cv = document.getElementById('cv');
                var ctx = cv.getContext('2d');
                var PAD = {l:44, r:14, t:10, b:32};
                // 고도 그래프는 X축 Ticker가 없으므로 하단 여백 최소화 (속도 그래프와 X축 공유)
                var PAD_BE = 6;
                var selectedProf = -1;   // 현재 선택된 프로파일 인덱스

                // 그래프 표시 구간 (좌우 확대/이동용)
                var viewA = 0, viewB = dist > 0 ? dist : 1;

                function fmtDist(d) {
                  return d >= 1000 ? (d/1000).toFixed(1)+'km' : Math.round(d)+'m';
                }
                function fmtTime(ms) {
                  var t = new Date(ms);
                  var hh = ('0'+t.getHours()).slice(-2);
                  var mm = ('0'+t.getMinutes()).slice(-2);
                  return hh+':'+mm;
                }
                // 특정 누적 거리에 가장 가까운 프로파일 지점의 기록 시각
                function timeAtDist(d) {
                  var best = -1, bestDiff = Infinity;
                  for (var i = 0; i < profDist.length; i++) {
                    var diff = Math.abs(profDist[i] - d);
                    if (diff < bestDiff) { bestDiff = diff; best = i; }
                  }
                  return best >= 0 ? pts[profIdx[best]][3] : null;
                }

                function xOf(d, w) { return PAD.l + (w-PAD.l-PAD.r) * ((d-viewA)/(viewB-viewA)); }

                // 선택 지점 값 라벨: 점에서 위로 떨어진 위치에 박스로 표시 (위 공간 부족 시 아래)
                function drawValueLabel(c, x, y, text, w, topLimit) {
                  c.font = '11px sans-serif';
                  var tw = c.measureText(text).width + 10;
                  var lx = Math.min(Math.max(x - tw/2, PAD.l+2), w - PAD.r - tw - 2);
                  var ly = y - 28;
                  if (ly < topLimit) ly = y + 12;
                  c.fillStyle = 'rgba(255,255,255,0.95)';
                  c.strokeStyle = '#E5322D'; c.lineWidth = 1;
                  c.beginPath();
                  c.rect(lx, ly, tw, 16);
                  c.fill(); c.stroke();
                  c.fillStyle = '#E5322D';
                  c.textAlign = 'left';
                  c.fillText(text, lx+5, ly+12);
                }
                function yOf(e, h, minE, maxE) {
                  var range = Math.max(maxE-minE, 1);
                  return PAD.t + (h-PAD.t-PAD_BE) * (1 - (e-minE)/range);
                }

                function drawChart() {
                  var w = cv.clientWidth, h = cv.clientHeight;
                  cv.width = w; cv.height = h;
                  ctx.clearRect(0,0,w,h);

                  if (profEle.length < 2) {
                    ctx.font = '12px sans-serif'; ctx.fillStyle = '#999';
                    ctx.fillText('고도 데이터가 없습니다', PAD.l, h/2);
                    return;
                  }
                  // 표시 구간 안의 최저/최고 고도 (확대 시 세로 스케일도 맞춰짐)
                  var minE = Infinity, maxE = -Infinity;
                  for (var i = 0; i < profEle.length; i++) {
                    if (profDist[i] < viewA || profDist[i] > viewB) continue;
                    if (profEle[i] < minE) minE = profEle[i];
                    if (profEle[i] > maxE) maxE = profEle[i];
                  }
                  if (!isFinite(minE)) {
                    minE = Math.min.apply(null, profEle);
                    maxE = Math.max.apply(null, profEle);
                  }

                  // 고도 눈금선 (범위에 맞는 보기 좋은 간격 자동 선택, 3~6줄)
                  var range = Math.max(maxE - minE, 1);
                  var steps = [1,2,5,10,20,50,100,200,500,1000];
                  var step = steps[steps.length-1];
                  for (var i = 0; i < steps.length; i++) {
                    if (range / steps[i] <= 6) { step = steps[i]; break; }
                  }
                  ctx.font = '10px sans-serif';
                  for (var e = Math.ceil(minE/step)*step; e <= maxE; e += step) {
                    var gy = yOf(e, h, minE, maxE);
                    ctx.beginPath();
                    ctx.moveTo(PAD.l, gy); ctx.lineTo(w-PAD.r, gy);
                    ctx.strokeStyle = '#E0E0E0'; ctx.lineWidth = 1;
                    ctx.stroke();
                    ctx.fillStyle = '#888';
                    ctx.fillText(e+'m', 4, gy+3);
                  }

                  // 곡선/면은 플롯 영역 밖으로 나가지 않도록 클리핑
                  ctx.save();
                  ctx.beginPath();
                  ctx.rect(PAD.l, 0, w-PAD.l-PAD.r, h-PAD_BE);
                  ctx.clip();

                  // 면 채우기
                  ctx.beginPath();
                  ctx.moveTo(xOf(profDist[0],w), h-PAD_BE);
                  for (var i = 0; i < profEle.length; i++) {
                    ctx.lineTo(xOf(profDist[i],w), yOf(profEle[i],h,minE,maxE));
                  }
                  ctx.lineTo(xOf(profDist[profDist.length-1],w), h-PAD_BE);
                  ctx.closePath();
                  ctx.fillStyle = 'rgba(79,195,247,0.25)';
                  ctx.fill();

                  // 고도 곡선
                  ctx.beginPath();
                  for (var i = 0; i < profEle.length; i++) {
                    var x = xOf(profDist[i],w), y = yOf(profEle[i],h,minE,maxE);
                    if (i === 0) ctx.moveTo(x,y); else ctx.lineTo(x,y);
                  }
                  ctx.strokeStyle = '#0288D1'; ctx.lineWidth = 1;
                  ctx.stroke();
                  ctx.restore();

                  // X축 Ticker 없음 — 아래 속도 그래프의 X축을 공유

                  // 선택 지점 표시 (세로선 + 점, 표시 구간 안일 때만)
                  if (selectedProf >= 0 &&
                      profDist[selectedProf] >= viewA && profDist[selectedProf] <= viewB) {
                    var sx = xOf(profDist[selectedProf],w);
                    var sy = yOf(profEle[selectedProf],h,minE,maxE);
                    ctx.beginPath();
                    ctx.moveTo(sx, PAD.t); ctx.lineTo(sx, h-PAD_BE);
                    ctx.strokeStyle = '#E5322D'; ctx.lineWidth = 1;
                    ctx.stroke();
                    ctx.beginPath();
                    ctx.arc(sx, sy, 4, 0, Math.PI*2);
                    ctx.fillStyle = '#E5322D';
                    ctx.fill();
                    drawValueLabel(ctx, sx, sy, Math.round(profEle[selectedProf]) + 'm', w, PAD.t);
                  }
                }
                // ───── 속도 그래프 (고도 그래프 아래, 같은 표시 구간 공유) ─────
                var cvs = document.getElementById('cvs');
                var ctx2 = cvs.getContext('2d');

                function drawSpd() {
                  var w = cvs.clientWidth, h = cvs.clientHeight;
                  cvs.width = w; cvs.height = h;
                  ctx2.clearRect(0,0,w,h);

                  if (spdVal.length < 2) {
                    ctx2.font = '12px sans-serif'; ctx2.fillStyle = '#999';
                    ctx2.fillText('속도 데이터가 없습니다', PAD.l, h/2);
                    return;
                  }

                  // 표시 구간 안의 최고 속도로 세로 스케일 결정 (0부터)
                  var maxS = 0;
                  for (var i = 0; i < spdVal.length; i++) {
                    if (spdDist[i] < viewA || spdDist[i] > viewB) continue;
                    if (spdVal[i] > maxS) maxS = spdVal[i];
                  }
                  if (maxS <= 0) maxS = Math.max.apply(null, spdVal);
                  maxS = Math.max(maxS * 1.05, 1);

                  function ySpd(v) { return PAD.t + (h-PAD.t-PAD.b) * (1 - v/maxS); }

                  // 속도 눈금선
                  var steps = [0.5,1,2,5,10,20,50,100], step = steps[steps.length-1];
                  for (var i = 0; i < steps.length; i++) {
                    if (maxS / steps[i] <= 5) { step = steps[i]; break; }
                  }
                  ctx2.font = '10px sans-serif';
                  for (var v = step; v <= maxS; v += step) {
                    var gy = ySpd(v);
                    ctx2.beginPath();
                    ctx2.moveTo(PAD.l, gy); ctx2.lineTo(w-PAD.r, gy);
                    ctx2.strokeStyle = '#E0E0E0'; ctx2.lineWidth = 1;
                    ctx2.stroke();
                    ctx2.fillStyle = '#888';
                    ctx2.fillText(v+'', 4, gy+3);
                  }
                  ctx2.fillStyle = '#888';
                  ctx2.fillText('km/h', 4, PAD.t);

                  // 면 + 곡선 (플롯 영역 클리핑)
                  ctx2.save();
                  ctx2.beginPath();
                  ctx2.rect(PAD.l, 0, w-PAD.l-PAD.r, h-PAD.b);
                  ctx2.clip();

                  ctx2.beginPath();
                  ctx2.moveTo(xOf(spdDist[0],w), h-PAD.b);
                  for (var i = 0; i < spdVal.length; i++) {
                    ctx2.lineTo(xOf(spdDist[i],w), ySpd(spdVal[i]));
                  }
                  ctx2.lineTo(xOf(spdDist[spdDist.length-1],w), h-PAD.b);
                  ctx2.closePath();
                  ctx2.fillStyle = 'rgba(255,143,0,0.18)';
                  ctx2.fill();

                  ctx2.beginPath();
                  for (var i = 0; i < spdVal.length; i++) {
                    var x = xOf(spdDist[i],w), y = ySpd(spdVal[i]);
                    if (i === 0) ctx2.moveTo(x,y); else ctx2.lineTo(x,y);
                  }
                  ctx2.strokeStyle = '#FF8F00'; ctx2.lineWidth = 1;
                  ctx2.stroke();
                  ctx2.restore();

                  // X축 Ticker: 거리 + 통과 시각 (고도 그래프와 동일)
                  var tickCount = w < 360 ? 3 : 5;
                  for (var i = 0; i < tickCount; i++) {
                    var frac = i / (tickCount - 1);
                    var d = viewA + (viewB - viewA) * frac;
                    var tx = xOf(d, w);
                    ctx2.beginPath();
                    ctx2.moveTo(tx, h-PAD.b); ctx2.lineTo(tx, h-PAD.b+4);
                    ctx2.strokeStyle = '#999'; ctx2.lineWidth = 1;
                    ctx2.stroke();
                    ctx2.textAlign = i === 0 ? 'left' : (i === tickCount-1 ? 'right' : 'center');
                    ctx2.fillStyle = '#555';
                    ctx2.fillText(fmtDist(d), tx, h-PAD.b+14);
                    var tms = timeAtDist(d);
                    if (tms !== null) {
                      ctx2.fillStyle = '#888';
                      ctx2.fillText(fmtTime(tms), tx, h-PAD.b+26);
                    }
                  }
                  ctx2.textAlign = 'left';

                  // 선택 지점 세로선 + 마커 + 속도값 라벨 (고도 그래프와 연동)
                  if (selectedProf >= 0 &&
                      profDist[selectedProf] >= viewA && profDist[selectedProf] <= viewB) {
                    var selD = profDist[selectedProf];
                    var sx = xOf(selD, w);
                    ctx2.beginPath();
                    ctx2.moveTo(sx, PAD.t); ctx2.lineTo(sx, h-PAD.b);
                    ctx2.strokeStyle = '#E5322D'; ctx2.lineWidth = 1;
                    ctx2.stroke();

                    // 선택 지점과 가장 가까운 속도 포인트에 점 + 값 라벨
                    var bi = -1, bd = Infinity;
                    for (var i = 0; i < spdDist.length; i++) {
                      var dd = Math.abs(spdDist[i] - selD);
                      if (dd < bd) { bd = dd; bi = i; }
                    }
                    if (bi >= 0) {
                      var sy = ySpd(spdVal[bi]);
                      ctx2.beginPath();
                      ctx2.arc(sx, sy, 4, 0, Math.PI*2);
                      ctx2.fillStyle = '#E5322D';
                      ctx2.fill();
                      drawValueLabel(ctx2, sx, sy, spdVal[bi].toFixed(1) + 'km/h', w, PAD.t);
                    }
                  }
                }

                function redraw() { drawChart(); drawSpd(); }
                redraw();
                window.addEventListener('resize', redraw);

                // ───── 지도/그래프 공통: 지점 선택 ─────
                function selectPoint(idx) {
                  var p = pts[idx];
                  var eleText = p[2] !== null ? p[2].toFixed(1) + ' m' : '정보 없음';
                  var timeText = p[3] !== null
                    ? new Date(p[3]).toLocaleTimeString('ko-KR')
                    : '정보 없음';
                  var content = '<b>고도</b> ' + eleText + '<br><b>시각</b> ' + timeText;

                  if (MapView) MapView.setSelected(latlngs[idx], content);

                  // 그래프에서 가장 가까운 프로파일 지점 찾아 표시
                  selectedProf = -1;
                  var best = Infinity;
                  for (var i = 0; i < profIdx.length; i++) {
                    var d = Math.abs(profIdx[i] - idx);
                    if (d < best) { best = d; selectedProf = i; }
                  }
                  redraw();
                }

                // 지도 터치 → 경로에서 가장 가까운 기록 지점 선택
                // (지도가 나중에 준비되는 경우를 위해 콜백을 보관해 두고 등록한다)
                mapClickCb = function(clicked) {
                  var best = 0, bestDist = Infinity;
                  for (var i = 0; i < latlngs.length; i++) {
                    var d = distOf(clicked, latlngs[i]);
                    if (d < bestDist) { bestDist = d; best = i; }
                  }
                  selectPoint(best);
                };
                if (MapView) MapView.onMapClick(mapClickCb);

                // 그래프 터치 → 해당 거리의 지점을 지도에 표시
                function chartTouch(clientX) {
                  var rect = cv.getBoundingClientRect();
                  var x = clientX - rect.left;
                  var w = cv.clientWidth;
                  var frac = (x - PAD.l) / (w - PAD.l - PAD.r);
                  frac = Math.max(0, Math.min(1, frac));
                  var target = viewA + (viewB - viewA) * frac;
                  // 누적 거리에서 가장 가까운 프로파일 지점 검색
                  var best = 0, bestDiff = Infinity;
                  for (var i = 0; i < profDist.length; i++) {
                    var d = Math.abs(profDist[i] - target);
                    if (d < bestDiff) { bestDiff = d; best = i; }
                  }
                  var idx = profIdx[best];
                  selectPoint(idx);
                  if (MapView) MapView.panTo(latlngs[idx]);
                }

                // 표시 구간 적용 (최소 1/50 ~ 전체 범위로 제한, 경계 밖 이동 방지)
                function applyView(a, b) {
                  var span = b - a;
                  var maxSpan = dist > 0 ? dist : 1;
                  var minSpan = maxSpan / 50;
                  if (span < minSpan) span = minSpan;
                  if (span > maxSpan) span = maxSpan;
                  var c = (a + b) / 2;
                  a = c - span/2; b = c + span/2;
                  if (a < 0) { b -= a; a = 0; }
                  if (b > maxSpan) { a -= (b - maxSpan); b = maxSpan; }
                  if (a < 0) a = 0;
                  viewA = a; viewB = b;
                  redraw();
                }

                // 제스처: 핀치=확대/축소, 드래그=이동, 탭=지점 선택, 더블탭=전체 보기
                // 고도/속도 그래프 양쪽 모두에서 동작
                function attachInteraction(c) {
                  var gesture = null, lastTap = 0;

                  c.addEventListener('click', function(e){ chartTouch(e.clientX); });

                  c.addEventListener('touchstart', function(e){
                    var rect = c.getBoundingClientRect();
                    var W = c.clientWidth - PAD.l - PAD.r;
                    function dAt(clientX) {
                      return viewA + (viewB - viewA) * (clientX - rect.left - PAD.l) / W;
                    }
                    if (e.touches.length === 2) {
                      gesture = {
                        mode: 'pinch',
                        d1: dAt(e.touches[0].clientX),
                        d2: dAt(e.touches[1].clientX)
                      };
                    } else if (e.touches.length === 1) {
                      // 한 손가락: 누른 즉시 마커 이동(스크럽) 시작
                      gesture = {mode:'scrub', x0:e.touches[0].clientX, moved:false};
                      chartTouch(e.touches[0].clientX);
                    }
                    e.preventDefault();
                  }, {passive:false});

                  c.addEventListener('touchmove', function(e){
                    if (!gesture) return;
                    var rect = c.getBoundingClientRect();
                    var W = c.clientWidth - PAD.l - PAD.r;
                    if (gesture.mode === 'pinch' && e.touches.length >= 2) {
                      var f1 = (e.touches[0].clientX - rect.left - PAD.l) / W;
                      var f2 = (e.touches[1].clientX - rect.left - PAD.l) / W;
                      if (Math.abs(f2 - f1) < 0.02) return;
                      var span = (gesture.d2 - gesture.d1) / (f2 - f1);
                      if (span <= 0) return;
                      var a = gesture.d1 - f1 * span;
                      applyView(a, a + span);
                    } else if (gesture.mode === 'scrub') {
                      // 드래그하는 동안 마커가 손가락 위치를 따라 이동
                      if (Math.abs(e.touches[0].clientX - gesture.x0) > 4) gesture.moved = true;
                      chartTouch(e.touches[0].clientX);
                    }
                    e.preventDefault();
                  }, {passive:false});

                  c.addEventListener('touchend', function(e){
                    if (gesture && gesture.mode === 'scrub' && !gesture.moved) {
                      // 움직임 없는 단순 탭: 더블탭이면 전체 보기로 리셋
                      var now = Date.now();
                      if (now - lastTap < 300) {
                        viewA = 0; viewB = dist > 0 ? dist : 1;
                        redraw();
                      }
                      lastTap = now;
                    }
                    if (e.touches.length === 0) gesture = null;
                  }, {passive:false});
                }
                attachInteraction(cv);
                attachInteraction(cvs);

                /* ───── 그래프 표시 ON/OFF (숨기면 지도 전체화면) ───── */
                var btnChart = document.getElementById('btn_chart');
                btnChart.addEventListener('click', function(){
                  var hidden = document.body.classList.toggle('nochart');
                  // 숨김 상태에서는 위쪽 화살표(펼치기), 표시 상태에서는 아래쪽 화살표(접기)
                  btnChart.innerHTML = hidden ? '&#9650;' : '&#9660;';
                  btnChart.title = hidden ? '그래프 보기' : '그래프 숨기기';
                  // 지도 영역이 바뀌었으니 지도 크기를 다시 맞춘다
                  if (MapView && MapView.resize) MapView.resize();
                  if (!hidden) redraw();
                });
              </script>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL("https://localhost/", html, "text/html", "utf-8", null)
    }

    private data class TrackPoint(
        val lat: Double,
        val lon: Double,
        val ele: Double?,
        val timeMillis: Long?
    )

    /** GPX 파일에서 트랙포인트(lat, lon, ele, time) 목록을 추출한다. */
    private fun parseTrackPoints(file: File): List<TrackPoint> {
        val blockRegex = Regex(
            """<trkpt\s+lat="([-\d.]+)"\s+lon="([-\d.]+)"\s*>(.*?)</trkpt>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val eleRegex = Regex("""<ele>([-\d.]+)</ele>""")
        val timeRegex = Regex("""<time>([^<]+)</time>""")
        val isoFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }

        return blockRegex.findAll(file.readText())
            .mapNotNull { m ->
                val lat = m.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
                val lon = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
                val body = m.groupValues[3]
                val ele = eleRegex.find(body)?.groupValues?.get(1)?.toDoubleOrNull()
                val time = timeRegex.find(body)?.groupValues?.get(1)?.let {
                    try { isoFormat.parse(it)?.time } catch (e: Exception) { null }
                }
                TrackPoint(lat, lon, ele, time)
            }
            .toList()
    }
}

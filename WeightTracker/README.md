# WeightTracker (몸무게 기록)

체중을 캘린더에 기록하고, 기간별 그래프로 추이를 확인할 수 있는 안드로이드 앱입니다.

## 주요 기능

- **캘린더 기록**: 달력에서 날짜를 탭해 체중(과 메모)을 입력/수정
- **연동 그래프 (홈 화면)**: 전체 그래프에서 지점을 터치하면 해당 연도의 년별 그래프가, 년별 그래프에서 지점을 터치하면 해당 월의 월별 그래프가 갱신됨
  - 전체 그래프는 핀치 줌/좌우 팬 가능
  - 목표 체중을 빨간 점선으로 표시, 년별/월별 그래프는 목표값을 Y축 중앙에 두고 표시
- **그래프 탭**: 1달 / 1년 / 전체 구간을 선택해서 보고, 이전/다음 기간으로 이동. 최소/최대/평균 통계 표시
- **목표 체중 설정**: SharedPreferences에 저장, 모든 그래프에 기준선으로 표시
- **CSV 가져오기/내보내기**: 다른 앱에서 CSV 파일을 공유하거나 열어서 가져오기 가능 (`yyyy-MM-dd`, `yyyy/MM/dd`, `MM/dd/yyyy`, `dd-MM-yyyy` 날짜 형식 자동 인식)

## 기술 스택

- Kotlin, MVVM (ViewModel + LiveData)
- Room (로컬 DB)
- Navigation Component (Fragment 전환)
- MPAndroidChart (그래프)
- View Binding

minSdk 26 / targetSdk·compileSdk 34

## 프로젝트 구조

```
app/src/main/java/com/weighttracker/app/
├── data/
│   ├── model/WeightEntry.kt        # date(PK, yyyy-MM-dd), weight, note
│   ├── db/WeightDao.kt             # Room DAO
│   ├── db/WeightDatabase.kt        # Room DB
│   └── repository/WeightRepository.kt
├── ui/
│   ├── home/                       # 캘린더 + 연동 그래프 화면
│   │   ├── HomeFragment.kt
│   │   ├── HomeViewModel.kt
│   │   ├── WeightEntryAdapter.kt
│   │   └── DateMarkerView.kt       # 그래프 터치 시 날짜/체중 마커
│   └── chart/                      # 기간 탭 그래프 화면
│       ├── ChartFragment.kt
│       └── ChartViewModel.kt
├── util/CsvUtil.kt                 # CSV 파싱/생성
└── MainActivity.kt
```

## 빌드

```bash
./gradlew assembleDebug
```

Android Studio에서 열어서 실행해도 됩니다.

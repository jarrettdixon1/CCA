package com.example.hugolucas.cca;

import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.example.hugolucas.cca.apiObjects.FixerResult;
import com.example.hugolucas.cca.apis.FixerApi;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.utils.EntryXComparator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import butterknife.BindView;
import butterknife.ButterKnife;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Created by hugolucas on 10/27/17.
 */

public class ExchangeFragment extends Fragment {

    private static String TAG = "exchange_fragment";

    private static final String DECADE = "DECADE";
    private static final String YEAR = "YEAR";
    private static final String MONTH = "MONTH";
    private static final String WEEK = "WEEK";

    private static String mCurrentInterval = WEEK;
    private static String mSourceCurrency = "USD";
    private static String mTargetCurrency = "GBP";

    @BindView(R.id.toggle_time_interval) FloatingActionButton mTimeToggleButton;
    @BindView(R.id.change_target_currency) FloatingActionButton mCurrencyChangeButton;
    @BindView(R.id.exchange_line_chart) LineChart mLineChart;

    private DataTable mDataTable;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mDataTable = new DataTable(mSourceCurrency, mTargetCurrency);
    }

    @Nullable @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        View view = inflater.inflate(R.layout.fragment_exchange, container, false);
        ButterKnife.bind(this, view);

        mLineChart.setBackgroundColor(getResources().getColor(R.color.graph_background_white));
        return view;
    }

    @Override
    public void onStart() {
        super.onStart();

        gatherData();
    }

    /**
     * Creates a list of dates needed to explain a certain time interval and calls the Fixer API
     * to get exchange rate information for those dates. Let the query method know which call is
     * the last call in the time interval in order to trigger the update of the graph view.
     */
    public void gatherData(){
        List<String> dateQueries = generateTimeLine(mCurrentInterval);
        int numberOfQueries = dateQueries.size();
        for (int i = 0; i < numberOfQueries; i ++){
            final String date = dateQueries.get(i);
            if (i + 1 == numberOfQueries)
                queryDatabase(date, mDataTable.getCurrencies(), true);
            else
                queryDatabase(date, mDataTable.getCurrencies(), false);
        }
    }

    /**
     * Uses the RetroFit interface for the Fixer API to get exchange rate information
     * asynchronously. On the last call, it initiates an AsyncTask to refresh the graph.
     *
     * @param date          query parameter, the date the exchange rate data was observed
     * @param currencies    a string of format [$$$],[$$$] used to query the API
     * @param lastCall      boolean signifying if this call is the last call needed for an interval
     */
    public void queryDatabase(final String date, String currencies, final boolean lastCall){
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("https://api.fixer.io/")
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        FixerApi fixer = retrofit.create(FixerApi.class);
        Call<FixerResult> result = fixer.getExchangeRates(date, currencies);
        Log.v(TAG, result.toString());
        result.enqueue(new Callback<FixerResult>() {

            @Override
            public void onResponse(Call<FixerResult> call, Response<FixerResult> response) {
                Log.v(TAG, "API call successful for: " + date);
                Map<String, Float> rates = response.body().getRates();
                mDataTable.parseMap(date, rates);

                if(lastCall)
                    new PopulateGraph().execute();
            }

            @Override
            public void onFailure(Call<FixerResult> call, Throwable t) {
                Log.v(TAG, "API call failed for: " + date);
                Log.v(TAG, t.getLocalizedMessage());
                Log.v(TAG, t.toString());
            }
        });
    }

    /**
     * Given a previously defined time interval, this method will create a list of String objects
     * whose value match a series of dates in those intervals. Most intervals are sampled rather
     * than fully queried due to time constraints.
     *
     * @param timeInterval      the time interval used to generate the list of String dates
     * @return                  a list of date String objects in the form of YYYY-MM-DD
     */
    public List<String> generateTimeLine(String timeInterval){
        ArrayList<String> timeline = new ArrayList<>();

        Calendar calendar = Calendar.getInstance();
        calendar.setTime(new Date());

        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");

        switch (timeInterval) {
            case WEEK: {
                for (int i = 0; i < 7; i ++) {
                    timeline.add(formatter.format(calendar.getTime()));
                    calendar.add(Calendar.DATE, -1);
                }
            }

            case MONTH: {
                for (int i = 0; i < 15; i += 3){
                    timeline.add(formatter.format(calendar.getTime()));
                    calendar.add(Calendar.DATE, -2);
                }
            }

            case YEAR: {
                for (int i = 0; i < 26; i += 3){
                    timeline.add(formatter.format(calendar.getTime()));
                    calendar.add(Calendar.WEEK_OF_YEAR, -2);
                }
            }

            case DECADE: {
                for (int i = 0; i < 20; i += 3){
                    timeline.add(formatter.format(calendar.getTime()));
                    calendar.add(Calendar.MONTH, -6);
                }
            }
        }

        return timeline;
    }

    /**
     * Class used to orgnize the results of the Fixer API into a format readable by the Graph
     * library used for this fragment.
     */
    private class DataTable{

        private String mSourceCurrency;
        private String mTargetCurrency;

        private Map<String, Float> mSourceMap;
        private Map<String, Float> mTargetMap;

        private Map<String, Integer> mIndexToDateMap;
        private int mCurrentIndex;

        private DataTable(String source, String target){
            mSourceCurrency = source;
            mTargetCurrency = target;

            mSourceMap = new HashMap<>();
            mTargetMap = new HashMap<>();

            mIndexToDateMap = new HashMap<>();
            mCurrentIndex = 0;
        }

        public synchronized void parseMap(String date, Map<String, Float> dataMap){
            for (String s: dataMap.keySet()){
                if (s.equals(mSourceCurrency))
                    mSourceMap.put(date, dataMap.get(s));
                else if (s.equals(mTargetCurrency))
                    mTargetMap.put(date, dataMap.get(s));
                else
                    throw new IllegalArgumentException("Wrong Response!");
                assignDateAnIndex(date);
            }
        }

        public synchronized void assignDateAnIndex(String date){
            if (mIndexToDateMap.get(date) == null){
                mIndexToDateMap.put(date, mCurrentIndex);
                mCurrentIndex += 1;
            }
        }

        public synchronized List<Entry> generateSourceEntries(){
            List<Entry> entries = new ArrayList<>();
            for (String key: mSourceMap.keySet())
                entries.add(new Entry(mIndexToDateMap.get(key), mSourceMap.get(key)));
            Collections.sort(entries, new EntryXComparator());
            return entries;
        }

        public synchronized List<Entry> generateTargetEntries(){
            List<Entry> entries = new ArrayList<>();
            for (String key: mTargetMap.keySet())
                entries.add(new Entry(mIndexToDateMap.get(key), mTargetMap.get(key)));
            Collections.sort(entries, new EntryXComparator());
            return entries;
        }

        public String getCurrencies(){
            return mSourceCurrency + "," + mTargetCurrency;
        }
    }

    /**
     * AsyncTask used to populate the Graph View with the data generated by the Fixer API.
     */
    public class PopulateGraph extends AsyncTask<Void, Void, Void>{

        private LineData data;

        @Override
        protected Void doInBackground(Void... voids) {
            List<Entry> sourceEntries = mDataTable.generateSourceEntries();
            List<Entry> targetEntries = mDataTable.generateTargetEntries();

            LineDataSet sourceData = new LineDataSet(sourceEntries, mSourceCurrency);
            sourceData.setColor(R.color.graph_data_blue);

            LineDataSet targetData = new LineDataSet(targetEntries, mTargetCurrency);
            targetData.setColor(R.color.graph_data_red);

            data = new LineData();
            data.addDataSet(sourceData);
            data.addDataSet(targetData);

            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);

            mLineChart.setData(data);
            mLineChart.invalidate();
        }
    }
}

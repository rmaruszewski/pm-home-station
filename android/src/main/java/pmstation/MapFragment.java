package pmstation;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.fragment.app.Fragment;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import pmstation.core.plantower.IPlanTowerObserver;
import pmstation.core.plantower.ParticulateMatterSample;
import pmstation.plantower.Settings;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MapFragment extends Fragment implements IPlanTowerObserver {
    private static final int PM25_NORM = 25;

    private GoogleMap map;
    private boolean zoomed = false;
    private Map<Circle, ParticulateMatterSample> samples = new HashMap<>();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_map, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            activity.removeValueObserver(this);
        }
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        MainActivity activity = (MainActivity) getActivity();
        activity.addValueObserver(this);

        SupportMapFragment supportMapFragment = (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.map);

        supportMapFragment.getMapAsync(map -> {
            MapFragment.this.map = map;
            map.setMyLocationEnabled(true);
            map.setOnCircleClickListener(customCircleOnClickListener());
            redrawSamples();
        });
    }

    @Override
    public void reset() {
        map.clear();
        samples.clear();
    };

    @Override
    public void update(ParticulateMatterSample sample) {
        drawSample(sample);
    }

    private void redrawSamples() {
        map.clear();
        samples.clear();
        List<ParticulateMatterSample> samples = ((MainActivity) getActivity()).getValues();

        for (ParticulateMatterSample sample : samples) {
            drawSample(sample);
        }
    }

    private void drawSample(ParticulateMatterSample sample) {
        if (map != null && sample.getLatitude() != null && sample.getLongitude() != null) {

            int color = AQIColor.fromPM25Level(sample.getPm2_5()).getColor();

            Circle circle = map.addCircle(new CircleOptions()
                    .center(new LatLng(sample.getLatitude(), sample.getLongitude()))
                    .radius(10)
                    .strokeWidth(20)
                    .strokeColor(ColorUtils.setAlphaComponent(color, 32))
                    .fillColor(ColorUtils.setAlphaComponent(color, 64))
                    .clickable(true));

            samples.put(circle, sample);

            if (!zoomed) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(sample.getLatitude(), sample.getLongitude()), 17));
                zoomed = true;
            }

        }
    }

    public GoogleMap.OnCircleClickListener customCircleOnClickListener() {
        return circle -> {
            ParticulateMatterSample sample = samples.get(circle);

            String pm25 = String.format(Locale.getDefault(), "PM 2.5: %d (%d%%)", sample.getPm2_5(), Math.round(sample.getPm2_5() * 1f / PM25_NORM * 100));
            String date = Settings.dateFormat.format(sample.getDate());

            map.addMarker(new MarkerOptions()
                    .alpha(0.0f)
                    .infoWindowAnchor(.6f, 1.0f)
                    .position(new LatLng(sample.getLatitude(), sample.getLongitude()))
                    .title(pm25)
                    .snippet(date)
            ).showInfoWindow();
        };
    }
}

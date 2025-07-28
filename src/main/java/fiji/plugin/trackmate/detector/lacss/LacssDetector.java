package fiji.plugin.trackmate.detector.lacss;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.protobuf.ByteString;

import biopb.image.BinData;
import biopb.image.DetectionRequest;
import biopb.image.DetectionResponse;
import biopb.image.DetectionSettings;
import biopb.image.ImageData;
import biopb.image.Pixels;
import biopb.image.Point;
import biopb.image.ROI;
import biopb.image.ScoredROI;
import fiji.plugin.trackmate.Logger;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotRoi;
import fiji.plugin.trackmate.detection.SpotDetector;
import fiji.plugin.trackmate.util.TMUtils;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converter;
import net.imglib2.converter.RealTypeConverters;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

public class LacssDetector<T extends RealType<T> & NativeType<T>> implements SpotDetector<T> {
	private final static String BASE_ERROR_MESSAGE = "LacssDetector: ";

	protected final ImgPlus<T> img;

	protected final Interval interval;

	protected final Map<String, Object> settings;

	private final Logger logger;

	protected String baseErrorMessage;

	protected String errorMessage;

	protected long processingTime;

	protected List<Spot> spots;

	private final LacssClient client;

	public LacssDetector(
			final ImgPlus<T> img,
			final Interval interval,
			final Map<String, Object> settings,
			final LacssClient client) {
		this.img = img;
		this.interval = interval;
		this.settings = settings;
		Logger logger = (Logger) settings.get(Constants.KEY_LOGGER);
		this.logger = (logger == null) ? Logger.VOID_LOGGER : logger;
		this.baseErrorMessage = BASE_ERROR_MESSAGE;
		this.client = client;
	}

	private long getDim(AxisType axis) {
		if (img.dimensionIndex(axis) >= 0) {

			return interval.dimension(img.dimensionIndex(axis));

		} else {
			return 0L;
		}
	}

	private long getIntervalSize() {
		long size = 1L;
		for (int i = 0; i < interval.numDimensions(); i++) {
			if ( interval.dimension(i) > 0 )
				size = size * interval.dimension(i);
		}
		return size;
	}

	private float getFloat(String key) {
		Double v = (Double) settings.get(key);
		return v.floatValue();
	}

	DetectionSettings getCurrentSettings() {
		DetectionSettings detectionSettings = DetectionSettings.newBuilder()
				.setMinScore(getFloat(Constants.KEY_DETECTION_THRESHOLD))
				.setMinCellArea(getFloat(Constants.KEY_MIN_CELL_AREA))
				.setNmsIou(getFloat(Constants.KEY_NMS_IOU))
				.setSegmentationThreshold(getFloat(Constants.KEY_SEGMENTATION_THRESHOLD))
				.setScalingHint(getFloat(Constants.KEY_SCALING))
				.build();

		return detectionSettings;
	}

	ImageData packImageData() {
		RandomAccessibleInterval<T> crop = Views.interval(img, interval);

		final double[] calibration = TMUtils.getSpatialCalibration(img);

		// add Z axis if needed at dim 2
		if (img.dimensionIndex(Axes.Z) <= 0) {
			int nd = crop.numDimensions();

			crop = Views.addDimension(crop, 0, 0);
			crop = Views.permute(crop, 2, nd);

		}

		// copy to byte array.
		Converter<RealType<T>, FloatType> converter = RealTypeConverters.getConverter(crop.getType(), new FloatType());
		ByteBuffer buffer = ByteBuffer.allocate((int) (getIntervalSize() * Float.BYTES));

		// flat iterator ensure XYZC order
		for (T pixel : Views.flatIterable(crop)) {

			FloatType value = new FloatType();

			converter.convert(pixel, value);

			buffer.putFloat(value.get());

		}

		// serialize
		BinData bindata = BinData.newBuilder()
				.setData(ByteString.copyFrom(buffer.array()))
				.setEndianness(BinData.Endianness.BIG)
				.build();

		Pixels pixels = Pixels.newBuilder()
				.setDimensionOrder("XYZCT")
				.setBindata(bindata)
				.setDtype("f4")
				.setSizeX((int) getDim(Axes.X))
				.setSizeY((int) getDim(Axes.Y))
				.setSizeZ((int) getDim(Axes.Z))
				.setPhysicalSizeX((float) calibration[0])
				.setPhysicalSizeY((float) calibration[1])
				.setPhysicalSizeZ((float) calibration[2])
				.build();

		ImageData imageData = ImageData.newBuilder()
				.setPixels(pixels)
				.build();

		return imageData;

	}

	boolean processResponse(DetectionResponse response) {
		final double[] calibration = TMUtils.getSpatialCalibration(img);

		int n_spots = response.getDetectionsCount();

		spots = new ArrayList<>(n_spots);

		for (ScoredROI scoredRoi : response.getDetectionsList()) {

			float score = scoredRoi.getScore();
			ROI roi = scoredRoi.getRoi();

			if (getDim(Axes.Z) == 0) {
				if (!roi.hasPolygon()) {

					errorMessage = baseErrorMessage + "data is 2D but server did not return polygons.";

					return false;
				}

				List<Point> points = roi.getPolygon().getPointsList();

				double[] x = new double[points.size()];
				double[] y = new double[points.size()];

				int cnt = 0;
				for (Point point : points) {

					x[cnt] = calibration[0] * (interval.min(0) + point.getX());
					y[cnt] = calibration[1] * (interval.min(1) + point.getY());

					cnt += 1;
				}

				spots.add(SpotRoi.createSpot(x, y, score * 100));

			} else {

				if (!roi.hasMesh()) {

					errorMessage = baseErrorMessage + "data is 3D but server did not return meshes.";

					return false;
				}

				float zc = 0, yc = 0, xc = 0, zc2 = 0, yc2 = 0, xc2 = 0;
				int nVerts = roi.getMesh().getVertsCount();

				for (Point vert : roi.getMesh().getVertsList()) {
					zc += vert.getZ();
					yc += vert.getY();
					xc += vert.getX();
					zc2 += vert.getZ() * vert.getZ();
					yc2 += vert.getY() * vert.getY();
					xc2 += vert.getX() * vert.getX();
				}

				zc = zc / nVerts;
				yc = yc / nVerts;
				xc = xc / nVerts;
				zc2 = zc2 / nVerts - zc * zc;
				yc2 = yc2 / nVerts - yc * yc;
				xc2 = xc2 / nVerts - xc * xc;

				double radius = Math.sqrt((zc2 + yc2 + xc2)) / 3;

				spots.add(new Spot(
						(xc + interval.min(0)) * calibration[0],
						(yc + interval.min(1)) * calibration[1],
						(zc + interval.min(2)) * calibration[2],
						radius,
						score * 100));
			}
		}

		return true;
	}

	@Override
	public boolean process() {
		boolean status = true;

		final long start = System.currentTimeMillis();

		DetectionRequest request = DetectionRequest.newBuilder()
				.setDetectionSettings(getCurrentSettings())
				.setImageData(packImageData())
				.build();

		try {

			// Logger.IJ_LOGGER.log("Connecting to server" + client.toString());

			DetectionResponse response = client.runDetection(request);

			if (response == null) {
				errorMessage = baseErrorMessage + "server returned error code: " 
					+ client.status.getCode() + "\n"
					+ client.status.getDescription();

				status = false;

			} else {

				status = processResponse(response);

			}

		} catch (InterruptedException e) {

			errorMessage = baseErrorMessage + e.getLocalizedMessage();

			status = false;

		}

		final long end = System.currentTimeMillis();

		this.processingTime = end - start;

		return status;
	}

	@Override
	public List<Spot> getResult() {
		return spots;
	}

	@Override
	public boolean checkInput() {
		if (null == img) {
			errorMessage = baseErrorMessage + "Image is null.";
			return false;
		}
		// if (img.dimensionIndex(Axes.Z) >= 0) {
		// errorMessage = baseErrorMessage + "Image must be 2D over time, got an image
		// with multiple Z.";
		// return false;
		// }
		return true;
	}

	@Override
	public String getErrorMessage() {
		return errorMessage;
	}

	@Override
	public long getProcessingTime() {
		return processingTime;
	}
}
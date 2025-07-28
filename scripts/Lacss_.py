#@ Dataset data
#@ String (label="BioPB Server", value="cellpose.biopb.org:443", columns=25) server_url
#@ String (label="BioPB server Token", required=False) token
#@ Double (label="Min Area", min=0, value=0) min_area
#@ Double (Lable="Min Score", min=0, max=1.0, style="format:#.##", value=0.4) min_score
#@ Double (label="Scaling", min=0.1, max=4.0, style="format:#.##", value=1.0) scaling
#@ Double (label="NMS Iou", min=0, max=1.0, style="format:#.##", value=0.0) nms

from java.util import HashMap

from ij import IJ

from net.imglib2.img import ImagePlusAdapter
from net.imagej.axis import Axes
from net.imglib2.img.display.imagej import ImgPlusViews

from fiji.plugin.trackmate import Settings
from fiji.plugin.trackmate.action import IJRoiExporter
from fiji.plugin.trackmate import Logger
from fiji.plugin.trackmate import Spot

from fiji.plugin.trackmate.detector.lacss import LacssUtils
from fiji.plugin.trackmate.detector.lacss import Constants
from fiji.plugin.trackmate.detector.lacss import LacssClient
from fiji.plugin.trackmate.detector.lacss import LacssDetector

def run(data, server_url, token, min_area, min_score, scaling, nms):
	imp = IJ.getImage()
	settings = Settings(imp)
	
	img = data.getImgPlus()
	itv = LacssUtils.getCurrentFrameInterval(img, settings)
	
	print(itv)
	
	parameters = HashMap()
	if token is None:
	    token = ""
	parameters.put( Constants.KEY_MIN_CELL_AREA, min_area )
	parameters.put( Constants.KEY_SCALING, scaling )
	parameters.put( Constants.KEY_NMS_IOU, nms )
	parameters.put( Constants.KEY_SEGMENTATION_THRESHOLD, 0.5 )
	parameters.put( Constants.KEY_DETECTION_THRESHOLD, min_score )
	parameters.put( Constants.KEY_MULTI_CHANNEL, True )
	parameters.put( Constants.KEY_LACSS_REMOTE_SERVER, server_url )
	parameters.put( Constants.KEY_LACSS_REMOTE_SERVER_TOKEN, token )
	parameters.put( Constants.KEY_LOGGER, None )
	
	client = LacssClient(server_url, token)
	detector = LacssDetector(img, itv, parameters, client)
	detector.process()
	spots = detector.getResult()
	
	for spot in spots:
	    # print(spot.getFeatures())
	    spot.putFeature( Spot.FRAME, imp.getFrame() )
	
	# print("Got result!")
	
	exporter = IJRoiExporter(imp, Logger.IJ_LOGGER)
	exporter.export(spots)
	
if __name__ in ("__builtin__", "__main__"):
    run(data, server_url, token, min_area, min_score, scaling, nms)

package kr.epinet.gdal.app.gdal2bin;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Properties;

import org.gdal.gdal.*;
import org.gdal.gdalconst.*;

public class Main {
	private static boolean _bReverse;
	private static String _inputFileName;
	private static String _outputFileName;
	private static int _w;
	private static int _h;
	private static double _noDataValue;
	private static double[] _geoTransform;
	private static int _dataTypeSize;
	private static ArrayList<String> _createOptionAry = new ArrayList<String>();
	private static String _outputFormat;

	public static void usage() {
		System.out.println("Usage: java gdal2bin");
		System.out.println("	[-of format]");
		System.out.println("	[-co \"NAME=VALUE\"]");
		System.out.println("	[-r]");
		System.out.println("	<src_filename> <dst_filename>");
		System.out.println("http://jrr.kr/388");
	}

	public static boolean readParams(String[] args) {
		if (args.length < 2) {
			usage();
			return false;
		}

		int iArgs = 0;
		for (iArgs = 0; iArgs < args.length; iArgs++) {
			if (args[iArgs].equals("-r")) {
				_bReverse = true;
			} else if (args[iArgs].equals("-co")) {
				if (args.length > iArgs + 1) {
					_createOptionAry.add(args[++iArgs]);
				}
			} else if( args[iArgs].equals("-of") ){
				if (args.length > iArgs + 1) {
					_outputFormat=	args[++iArgs];
				}
			} else if (_inputFileName == null) {
				_inputFileName = args[iArgs];
			} else if (_outputFileName == null) {
				_outputFileName = args[iArgs];
			} else {
				System.out.format("'%s'는 알 수 없는 파라미터입니다.\n", args[iArgs]);
				usage();
				return false;
			}
		}

		return true;
	}

	public static void main(String[] args) {
		if (!readParams(args)) {
			return;
		}

		gdal.AllRegister();

		if (_bReverse) {
			System.out.println("Starting bin to gdal.");
			if (readExtendFile()) {
				createGDALRaster();
			}
		} else {
			System.out.println("Starting gdal to bin.");
			if (createBinaryFile()) {
				writeExtendFile();
			}
		}
		System.out.println("Complete.");
	}

	public static boolean createBinaryFile() {
		Dataset hDataset = gdal.Open(_inputFileName, gdalconstConstants.GA_ReadOnly);
		if (hDataset == null) {
			System.out.format("파일 열기 실패: %s\n", _inputFileName);
			return false;
		}

		_w = hDataset.getRasterXSize();
		_h = hDataset.getRasterYSize();
		_geoTransform = hDataset.GetGeoTransform();

		Band hBand = hDataset.GetRasterBand(1);
		_noDataValue = Double.MAX_VALUE;
		{
			Double[] tmp = new Double[1];
			hBand.GetNoDataValue(tmp);
			_noDataValue = tmp[0];
		}

		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(_outputFileName);
		} catch (FileNotFoundException e) {
			System.out.format("파일 생성 실패: %s\n", _outputFileName);
			e.printStackTrace();
			return false;
		}
		FileChannel fc = fos.getChannel();
		System.out.format("래스터 파일 생성: %s\n", _outputFileName);

		_dataTypeSize = gdal.GetDataTypeSize(hBand.GetRasterDataType());

		try {
			for (int y = 0; y < _h; ++y) {
				fc.write(hBand.ReadRaster_Direct(0, y, _w, 1, hBand.GetRasterDataType()));
			}
		} catch (IOException e) {
			System.out.format("래스터 파일 쓰기 실패!");
			e.printStackTrace();
			return false;
		}

		try {
			fos.close();
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}

		return true;
	}

	public static boolean readExtendFile() {
		Properties prop = new Properties();
		try {
			prop.load(new FileReader(_inputFileName.substring(0, _inputFileName.length() - 4) + ".txt"));
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
		_w = Integer.parseInt(prop.getProperty("grid_ncols"));
		_h = Integer.parseInt(prop.getProperty("grid_nrows"));
		_dataTypeSize = Integer.parseInt(prop.getProperty("grid_nbits"));
		_geoTransform = new double[6];
		_geoTransform[1] = Double.parseDouble(prop.getProperty("grid_pixelwidth"));
		_geoTransform[5] = Double.parseDouble(prop.getProperty("grid_pixelheight"));
		_geoTransform[0] = Double.parseDouble(prop.getProperty("grid_xulcenter")) - (_geoTransform[1] / 2);
		_geoTransform[3] = Double.parseDouble(prop.getProperty("grid_yulcenter")) - (_geoTransform[5] / 2);
		_geoTransform[2] = 0;
		_geoTransform[4] = 0;
		_noDataValue = Double.parseDouble(prop.getProperty("grid_nullvalue"));
		return true;
	}

	public static boolean createGDALRaster() {
		int rasterType = gdalconstConstants.GDT_Byte;
		switch (_dataTypeSize) {
		case 16:
			rasterType = gdalconstConstants.GDT_Int16;
			break;
		case 32:
			rasterType = gdalconstConstants.GDT_Float32;
			break;
		case 64:
			rasterType = gdalconstConstants.GDT_Float64;
			break;
		}

		String[] opts = _createOptionAry.toArray(new String[_createOptionAry.size()]);

		Driver hDriver = gdal.GetDriverByName(_outputFormat==null?"GTiff":_outputFormat);
		Dataset hDataset = hDriver.Create(_outputFileName, _w, _h, 1, rasterType, opts);
		if (hDataset == null) {
			System.out.format("파일 생성 실패: %s\n", _inputFileName);
			return false;
		}
		hDataset.SetGeoTransform(_geoTransform);
		Band hBand = hDataset.GetRasterBand(1);
		hBand.SetNoDataValue(_noDataValue);

		FileInputStream fis = null;
		try {
			fis = new FileInputStream(_inputFileName);
		} catch (FileNotFoundException e) {
			System.out.format("파일 읽기 실패: %s\n", _inputFileName);
			e.printStackTrace();
			return false;
		}
		FileChannel fc = fis.getChannel();

		ByteBuffer bb = ByteBuffer.allocateDirect(_w * _dataTypeSize / 8);

		try {
			int y = 0;
			while (fc.read(bb) != -1) {
				hBand.WriteRaster_Direct(0, y, _w, 1, hBand.GetRasterDataType(), bb);
				y++;
				bb.clear();
			}
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		} finally {
			try {
				fis.close();
			} catch (IOException e) {
				e.printStackTrace();
				return false;
			}
		}
		
		
		return true;
	}

	public static boolean writeExtendFile() {
		int iLastSep = _outputFileName.lastIndexOf("/");
		if (iLastSep == -1) {
			iLastSep = _outputFileName.lastIndexOf("\\");
		}
		int iLastPeriod = _outputFileName.lastIndexOf(".");

		String extendFileName = null;
		if (iLastPeriod > iLastSep) {
			extendFileName = _outputFileName.substring(0, iLastPeriod + 1) + "txt";
		} else {
			extendFileName = _outputFileName + ".txt";
		}

		FileWriter fw = null;

		try {
			fw = new FileWriter(extendFileName);
		} catch (IOException e) {
			System.out.format("영역 파일 생성 실패: %s", extendFileName);
			e.printStackTrace();
			return false;
		}

		try {
			fw.write(String.format("grid_nrows=%d\n", _h));
			fw.write(String.format("grid_ncols=%d\n", _w));
			fw.write(String.format("grid_nbits=%d\n", _dataTypeSize));
			fw.write(String.format("grid_rowbytes=%d\n", _w * _dataTypeSize / 8));
			fw.write(String.format("grid_pixelwidth=%f\n", _geoTransform[1]));
			fw.write(String.format("grid_pixelheight=%f\n", _geoTransform[5]));
			fw.write(String.format("grid_xulcenter=%f\n", _geoTransform[0] + (_geoTransform[1] / 2)));
			fw.write(String.format("grid_yulcenter=%f\n", _geoTransform[3] + (_geoTransform[5] / 2)));
			fw.write(String.format("grid_nullvalue=%f\n", _noDataValue));
		} catch (IOException e) {
			System.out.format("영역 파일 쓰기 실패!");
			e.printStackTrace();
			return false;
		} finally {
			try {
				fw.close();
			} catch (IOException e) {
				e.printStackTrace();
				return false;
			}
		}

		return true;
	}
}

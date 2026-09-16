#include "MultiThreadedDecoder.h"
#include "cimb_translator/CimbDecoder.h"
#include "cimb_translator/CimbReader.h"
#include "encoder/Decoder.h"
#include "extractor/Scanner.h"
#include "serialize/format.h"

#include <jni.h>
#include <android/log.h>
#include <opencv2/core/core.hpp>
#include <opencv2/core/ocl.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include <algorithm>
#include <memory>
#include <mutex>
#include <set>
#include <sstream>
#include <vector>

#define TAG "CameraFileCopyCPP"

using namespace std;
using namespace cv;

namespace {
	std::shared_ptr<MultiThreadedDecoder> _proc;
	std::mutex _mutex; // for _proc and _completed
	std::set<std::string> _completed; // file names we already reported to the app

	unsigned _calls = 0;
	int _transferStatus = 0;
	clock_t _frameDecodeSnapshot = 0;
	clock_t _frameSuccessSnapshot = 0;

	unsigned millis(unsigned num, unsigned denom)
	{
		if (!denom)
			denom = 1;
		return (num / denom) * 1000 / CLOCKS_PER_SEC;
	}

	unsigned percent(unsigned num, unsigned denom)
	{
		if (!denom)
			denom = 1;
		return (num * 100) / denom;
	}

	void drawGuidance(cv::Mat& mat, int in_progress)
	{
		int minsz = std::min(mat.cols, mat.rows);
		int guideWidth = minsz >> 7;
		int outlineWidth = guideWidth + (minsz >> 8);
		int guideLength = guideWidth << 3;
		int guideOffset = minsz >> 5;
		int outlineOffset = (outlineWidth - guideWidth) >> 1;

		cv::Scalar color = cv::Scalar(255,255,255);
		if (in_progress == 1)
			color = cv::Scalar(255,244,94); // 0,191,255
		else if (in_progress == 2)
			color = cv::Scalar(0,255,0);
		cv::Scalar outline = cv::Scalar(0,0,0);

		int xextra = 0;
		if (mat.cols > mat.rows)
			xextra = (mat.cols - mat.rows) >> 1;
		int yextra = 0;
		if (mat.rows > mat.cols)
			yextra = (mat.rows - mat.cols) >> 1;

		int lx = guideOffset + xextra;
		int ty = guideOffset + yextra;
		int outlinex = lx - outlineOffset;
		int outliney = ty - outlineOffset;
		cv::line(mat, cv::Point(lx, outliney), cv::Point(lx + guideLength, outliney), outline, outlineWidth);
		cv::line(mat, cv::Point(outlinex, ty), cv::Point(outlinex, ty + guideLength), outline, outlineWidth);
		cv::line(mat, cv::Point(lx, ty), cv::Point(lx + guideLength, ty), color, guideWidth);
		cv::line(mat, cv::Point(lx, ty), cv::Point(lx, ty + guideLength), color, guideWidth);

		int rx = mat.cols - guideOffset - guideWidth - xextra;
		outlinex = rx + outlineOffset;
		outliney = ty - outlineOffset;
		cv::line(mat, cv::Point(rx, outliney), cv::Point(rx - guideLength, outliney), outline, outlineWidth);
		cv::line(mat, cv::Point(outlinex, ty), cv::Point(outlinex, ty + guideLength), outline, outlineWidth);
		cv::line(mat, cv::Point(rx, ty), cv::Point(rx - guideLength, ty), color, guideWidth);
		cv::line(mat, cv::Point(rx, ty), cv::Point(rx, ty + guideLength), color, guideWidth);

		int by = mat.rows - guideOffset - guideWidth - yextra;
		outlinex = lx - outlineOffset;
		outliney = by + outlineOffset;
		cv::line(mat, cv::Point(lx, outliney), cv::Point(lx + guideLength, outliney), outline, outlineWidth);
		cv::line(mat, cv::Point(outlinex, by), cv::Point(outlinex, by - guideLength), outline, outlineWidth);
		cv::line(mat, cv::Point(lx, by), cv::Point(lx + guideLength, by), color, guideWidth);
		cv::line(mat, cv::Point(lx, by), cv::Point(lx, by - guideLength), color, guideWidth);
	}

	void drawProgress(cv::Mat& mat, const std::vector<double>& progress)
	{
		if (progress.empty())
			return;

		int minsz = std::min(mat.cols, mat.rows);
		int fillHeight = std::max(3, minsz >> 6);
		int trackHeight = fillHeight + std::max(2, minsz >> 8);

		// horizontal, along the bottom and full width: the old vertical bars hugged the left
		// edge and read as a glitch in portrait. The button bar is raised in activity_main.xml
		// so it does not sit on top of the right end of this.
		int barLength = mat.cols;
		int barY = mat.rows - (minsz >> 4);
		int step = trackHeight * 2; // a second file in flight stacks above the first

		cv::Scalar color = cv::Scalar(255,255,255);
		cv::Scalar outline = cv::Scalar(0,0,0);

		for (double p : progress)
		{
			int fillLength = int(barLength * p);
			cv::Point left(0, barY);
			cv::Point right(barLength, barY);
			cv::line(mat, left, right, outline, trackHeight);
			if (fillLength > 0)
				cv::line(mat, left, cv::Point(fillLength, barY), color, fillHeight);

			barY -= step;
		}
	}

	void drawDebugInfo(cv::Mat& mat, MultiThreadedDecoder& proc)
	{
		std::stringstream sstop;
		sstop << "cfc using " << proc.num_threads() << " thread(s). " << proc.mode() << ":" << proc.detected_mode() << "..." << proc.backlog() << "? ";
		sstop << (MultiThreadedDecoder::bytes / std::max<double>(1, MultiThreadedDecoder::decoded)) << "b v0.6.8";
		std::stringstream ssmid;
		ssmid << "#: " << MultiThreadedDecoder::perfect << " / " << MultiThreadedDecoder::decoded << " / " << MultiThreadedDecoder::scanned << " / " << _calls;
		std::stringstream ssperf;
		ssperf << "scan: " << millis(MultiThreadedDecoder::scanTicks, MultiThreadedDecoder::scanned);
		ssperf << ", extract: " << millis(MultiThreadedDecoder::extractTicks, MultiThreadedDecoder::decoded);
		ssperf << ", decode: " << millis(MultiThreadedDecoder::decodeTicks, MultiThreadedDecoder::decoded);
		std::stringstream sstats;
		sstats << "Files received: " << proc.files_decoded() << ", in flight: " << proc.files_in_flight() << ". ";
		sstats << percent(MultiThreadedDecoder::perfect, MultiThreadedDecoder::decoded) << "% decode. ";
		sstats << percent(MultiThreadedDecoder::decoded, MultiThreadedDecoder::scanned) << "% scan.";

		cv::putText(mat, sstop.str(), cv::Point(5,50), cv::FONT_HERSHEY_DUPLEX, 1, cv::Scalar(255,255,80), 2);
		cv::putText(mat, ssmid.str(), cv::Point(5,100), cv::FONT_HERSHEY_DUPLEX, 1, cv::Scalar(255,255,80), 2);
		cv::putText(mat, ssperf.str(), cv::Point(5,150), cv::FONT_HERSHEY_DUPLEX, 1, cv::Scalar(255,255,80), 2);
		cv::putText(mat, sstats.str(), cv::Point(5,200), cv::FONT_HERSHEY_DUPLEX, 1, cv::Scalar(255,255,80), 2);

		/*std::stringstream ssperf2;
		ssperf2 << "reader ctor: " << millis(Decoder::readerInitTicks, MultiThreadedDecoder::decoded);
		ssperf2 << ", rss: " << millis(Decoder::rssTicks, MultiThreadedDecoder::decoded);
		ssperf2 << ", symbol: " << millis(Decoder::symbolTicks, MultiThreadedDecoder::decoded);
		ssperf2 << ", color: " << millis(Decoder::colorTicks, MultiThreadedDecoder::decoded);
		ssperf2 << ", ccm: " << millis(Decoder::ccmTicks, MultiThreadedDecoder::decoded);
		cv::putText(mat, ssperf2.str(), cv::Point(5,250), cv::FONT_HERSHEY_DUPLEX, 1, cv::Scalar(255,255,80), 2);
		//*/
	}

	std::string jstring_to_cppstr(JNIEnv *env, const jstring& dataPathObj)
	{
		const char* temp = env->GetStringUTFChars(dataPathObj, NULL);
		string res(temp);
		env->ReleaseStringUTFChars(dataPathObj, temp);
		return res;
	}
}

extern "C" {
jobjectArray JNICALL
Java_com_github_issakk_cfc_MainActivity_processImageJNI(JNIEnv *env, jobject instance, jlong matAddr, jstring dataPathObj, jint modeInt)
{
	++_calls;

	// get params from raw address
	Mat &mat = *(Mat *) matAddr;
	string dataPath = jstring_to_cppstr(env, dataPathObj);
	int modeVal = (int)modeInt;

	std::shared_ptr<MultiThreadedDecoder> proc;
	{
		std::lock_guard<std::mutex> lock(_mutex);
		if (!_proc or !_proc->set_mode(modeVal))
			_proc = std::make_shared<MultiThreadedDecoder>(dataPath, modeVal);
		proc = _proc;
	}

	clock_t begin = clock();
	cv::Mat img = mat.clone();
	proc->add(img);

	if ((_calls & 31) == 1)
	{
		clock_t decodeSnapshot = proc->decoded;
		clock_t perfectSnapshot = proc->perfect;
		_transferStatus = perfectSnapshot > _frameSuccessSnapshot; // a bit silly, but 1 == partial decode
		_transferStatus += (decodeSnapshot > _frameDecodeSnapshot); // 2 == full decode
		_frameDecodeSnapshot = decodeSnapshot;
		_frameSuccessSnapshot = perfectSnapshot;
	}

	drawProgress(mat, proc->get_progress());
	drawGuidance(mat, _transferStatus);
	//drawDebugInfo(mat, *proc);

	// log computation time to Android Logcat
	double totalTime = double(clock() - begin) / CLOCKS_PER_SEC;
	__android_log_print(ANDROID_LOG_INFO, TAG, "processImage computation time = %f seconds\n",
						totalTime);

	// report *every* file that finished since the last call -- not just the newest one
	//
	// known ceiling: _completed is keyed by file name, and the decoder sink keeps a
	// finished file in its "done" list for the rest of the session. Re-sending the
	// same file name inside one session is therefore reported only once; the second
	// copy waits in filesDir until the next launch picks it up as a leftover.
	std::vector<std::string> newFiles;
	{
		std::lock_guard<std::mutex> lock(_mutex);
		for (const string& s : proc->get_done())
			if (_completed.insert(s).second)
				newFiles.push_back(s);
	}

	jclass stringClass = env->FindClass("java/lang/String");
	if (!stringClass)
		return nullptr;
	jobjectArray result = env->NewObjectArray((jsize)newFiles.size(), stringClass, nullptr);
	if (!result)
		return nullptr;

	for (jsize i = 0; i < (jsize)newFiles.size(); ++i)
	{
		jstring jstr = env->NewStringUTF(newFiles[i].c_str());
		if (!jstr)
			continue; // non-utf8 filename or oom: leave the slot empty, the app skips nulls
		env->SetObjectArrayElement(result, i, jstr);
		env->DeleteLocalRef(jstr);
	}
	return result;
}

jdoubleArray JNICALL
Java_com_github_issakk_cfc_MainActivity_getStatusJNI(JNIEnv *env, jobject instance) {
	std::shared_ptr<MultiThreadedDecoder> proc;
	{
		std::lock_guard<std::mutex> lock(_mutex);
		proc = _proc;
	}

	double progress = 0;
	double inFlight = 0;
	if (proc)
	{
		for (double p : proc->get_progress())
			progress = std::max(progress, p);
		inFlight = proc->files_in_flight();
	}

	// {max progress 0..1, transfer status: 0 idle / 1 partial / 2 full, files in flight}
	//
	// known ceiling: the sink keeps an unfinished stream for the whole session, so a transfer
	// abandoned by the sender (file switched mid-stream) keeps contributing its frozen progress
	// to this max. It only shows up as a percentage that stops making sense after switching
	// files; the fix would be a per-stream idle timeout in the sink, which lives upstream.
	//
	// in-flight matters to the caller: _transferStatus only reports whether the last sampled
	// frames decoded anything, so it drops to 0 whenever the sender is briefly unreadable --
	// even with a file at 80%. The app decides "receiving" from the stream count instead.
	jdouble stats[3] = { progress, (double)_transferStatus, inFlight };
	jdoubleArray result = env->NewDoubleArray(3);
	if (result)
		env->SetDoubleArrayRegion(result, 0, 3, stats);
	return result;
}

jint JNICALL
Java_com_github_issakk_cfc_MainActivity_detectedModeJNI(JNIEnv *env, jobject instance) {
	std::lock_guard<std::mutex> lock(_mutex);
	return _proc ? (jint)_proc->detected_mode() : 0;
}

void JNICALL
Java_com_github_issakk_cfc_MainActivity_shutdownJNI(JNIEnv *env, jobject instance) {
	__android_log_print(ANDROID_LOG_INFO, TAG, "Shutdown cfc-cpp\n");

	std::lock_guard<std::mutex> lock(_mutex);
	if (_proc)
		_proc->stop();
	_proc = nullptr;

	// fresh decoder state for the next activity: the same file name can be received again
	_completed.clear();
	_calls = 0;
	_transferStatus = 0;
	_frameDecodeSnapshot = 0;
	_frameSuccessSnapshot = 0;
}

}

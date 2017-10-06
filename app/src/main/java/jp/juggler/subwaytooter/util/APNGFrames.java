package jp.juggler.subwaytooter.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import net.ellerton.japng.PngScanlineBuffer;
import net.ellerton.japng.argb8888.Argb8888Bitmap;
import net.ellerton.japng.argb8888.Argb8888Processor;
import net.ellerton.japng.argb8888.Argb8888Processors;
import net.ellerton.japng.argb8888.Argb8888ScanlineProcessor;
import net.ellerton.japng.argb8888.BasicArgb8888Director;
import net.ellerton.japng.chunks.PngAnimationControl;
import net.ellerton.japng.chunks.PngFrameControl;
import net.ellerton.japng.chunks.PngHeader;
import net.ellerton.japng.error.PngException;
import net.ellerton.japng.reader.DefaultPngChunkReader;
import net.ellerton.japng.reader.PngReadHelper;
import net.ellerton.japng.reader.PngReader;

import java.io.InputStream;
import java.util.ArrayList;

// APNGを解釈した結果を保持する
// (フレーム数分のbitmapと時間情報)

@SuppressWarnings("WeakerAccess") public class APNGFrames {
	
	static final LogCategory log = new LogCategory( "APNGFrames" );
	
	/**
	 * Keep a 1x1 transparent image around as reference for creating a scaled starting bitmap.
	 * Considering this because of some reported OutOfMemory errors, and this post:
	 * <p>
	 * http://stackoverflow.com/a/8527745/963195
	 * <p>
	 * Specifically: "NEVER use Bitmap.createBitmap(width, height, Config.ARGB_8888). I mean NEVER!"
	 * <p>
	 * Instead the 1x1 image (68 bytes of resources) is scaled up to the needed size.
	 * Whether or not this fixes the OOM problems is TBD...
	 */
	//static Bitmap sOnePxTransparent;
	static Paint sSrcModePaint;
	
	static Bitmap createBlankBitmap( int w, int h ){
		if( sSrcModePaint == null ){
			sSrcModePaint = new Paint();
			sSrcModePaint.setXfermode( new PorterDuffXfermode( PorterDuff.Mode.SRC ) );
			sSrcModePaint.setFilterBitmap( true );
		}
		//		if( sOnePxTransparent == null ){
		//			sOnePxTransparent = BitmapFactory.decodeResource( resources, R.drawable.onepxtransparent );
		//		}
		return Bitmap.createBitmap( w, h, Bitmap.Config.ARGB_8888 );
	}
	
	// WARNING: ownership of "src" will be moved or recycled.
	static Bitmap scaleBitmap( Bitmap src, int size_max ){
		if( src == null ) return null;
		
		int src_w = src.getWidth();
		int src_h = src.getHeight();
		if( src_w <= size_max && src_h <= size_max ) return src;
		
		int dst_w;
		int dst_h;
		if( src_w >= src_h ){
			dst_w = size_max;
			dst_h = (int) ( 0.5f + src_h * size_max / (float) src_w );
			if( dst_h < 1 ) dst_h = 1;
		}else{
			dst_h = size_max;
			dst_w = (int) ( 0.5f + src_w * size_max / (float) src_h );
			if( dst_w < 1 ) dst_w = 1;
		}
		
		// この方法だとリークがあるらしい？？？
		// http://stackoverflow.com/a/8527745/963195
		// return Bitmap.createScaledBitmap( src, dst_w , dst_h , true );
		
		Bitmap b2 = createBlankBitmap( dst_w, dst_h );
		Canvas canvas = new Canvas( b2 );
		Rect rect_src = new Rect( 0, 0, src_w, src_h );
		Rect rect_dst = new Rect( 0, 0, dst_w, dst_h );
		canvas.drawBitmap( src, rect_src, rect_dst, sSrcModePaint );
		src.recycle();
		return b2;
	}
	
	static Bitmap toBitmap( Argb8888Bitmap src ){
		int offset = 0;
		int stride = src.width;
		return Bitmap.createBitmap( src.getPixelArray(), offset, stride, src.width, src.height, Bitmap.Config.ARGB_8888 );
	}
	
	static Bitmap toBitmap( Argb8888Bitmap src, int size_max ){
		return scaleBitmap( toBitmap( src ), size_max );
	}
	
	//////////////////////////////////////////////////////
	
	// ピクセルサイズ制限
	private int mPixelSizeMax;
	
	// APNGじゃなかった場合に使われる
	private Bitmap mBitmapNonAnimation;
	
	private PngHeader header;
	private Argb8888ScanlineProcessor scanlineProcessor;
	private Bitmap canvasBitmap;
	private Canvas canvas;
	
	private PngAnimationControl animationControl;
	
	private long time_total = 0L;
	
	private ArrayList< Frame > frames;
	
	private static class Frame {
		final Bitmap bitmap;
		final long time_start;
		final long time_width;
		
		Frame( Bitmap bitmap, long time_start, long time_width ){
			this.bitmap = bitmap;
			this.time_start = time_start;
			this.time_width = time_width;
		}
	}
	
	///////////////////////////////////////////////////////////////
	
	APNGFrames( @NonNull Bitmap bitmap ){
		this.mBitmapNonAnimation = bitmap;
	}
	
	APNGFrames(
		@NonNull PngHeader header
		, @NonNull Argb8888ScanlineProcessor scanlineProcessor
		, @NonNull PngAnimationControl animationControl
		, int size_max
	){
		this.header = header;
		this.scanlineProcessor = scanlineProcessor;
		this.animationControl = animationControl;
		this.mPixelSizeMax = size_max;
		
		this.canvasBitmap = createBlankBitmap( this.header.width, this.header.height );
		this.canvas = new Canvas( this.canvasBitmap );
		this.frames = new ArrayList<>( animationControl.numFrames );
		
	}
	
	void onParseComplete(){
		if( frames != null && frames.size() <= 1 ){
			mBitmapNonAnimation = toBitmap( scanlineProcessor.getBitmap(), mPixelSizeMax );
		}
		
		if( canvasBitmap != null ){
			canvasBitmap.recycle();
			canvasBitmap = null;
		}
	}
	
	void dispose(){
		if( mBitmapNonAnimation != null ){
			mBitmapNonAnimation.recycle();
		}
		if( canvasBitmap != null ){
			canvasBitmap.recycle();
		}
		if( frames != null ){
			for( Frame f : frames ){
				f.bitmap.recycle();
			}
		}
	}
	
	PngFrameControl currentFrame;
	
	// フレームが追加される
	Argb8888ScanlineProcessor beginFrame( PngFrameControl frameControl ){
		currentFrame = frameControl;
		return scanlineProcessor.cloneWithSharedBitmap( header.adjustFor( currentFrame ) );
	}
	
	// フレームが追加される
	void completeFrame( Argb8888Bitmap frameImage ){
		// APNGのフレーム画像をAndroidの形式に変換する
		Bitmap frame = toBitmap( frameImage );
		
		Bitmap previous = null;
		// Capture the current bitmap region IF it needs to be reverted after rendering
		if( 2 == currentFrame.disposeOp ){
			previous = Bitmap.createBitmap( canvasBitmap, currentFrame.xOffset, currentFrame.yOffset, currentFrame.width, currentFrame.height ); // or could use from frames?
			//System.out.println(String.format("Captured previous %d x %d", previous.getWidth(), previous.getHeight()));
		}
		
		Paint paint = null; // (for blend, leave paint null)
		if( 0 == currentFrame.blendOp ){ // SRC_OVER, not blend
			paint = sSrcModePaint;
		}
		
		// boolean isFull = currentFrame.height == header.height && currentFrame.width == header.width;
		
		// Draw the new frame into place
		canvas.drawBitmap( frame, currentFrame.xOffset, currentFrame.yOffset, paint );
		
		// Extract a drawable from the canvas. Have to copy the current bitmap.
		// Store the drawable in the sequence of frames
		long time_start = time_total;
		long time_width = currentFrame.getDelayMilliseconds();
		if( time_width <= 0L ) time_width = 1L;
		time_total = time_start + time_width;
		
		frames.add( new Frame(
			scaleBitmap( canvasBitmap.copy( Bitmap.Config.ARGB_8888, false ), mPixelSizeMax )
			, time_start
			, time_width
		) );
		
		// Now "dispose" of the frame in preparation for the next.
		// https://wiki.mozilla.org/APNG_Specification#.60fcTL.60:_The_Frame_Control_Chunk
		
		switch( currentFrame.disposeOp ){
		default:
			// Default should never happen
		
		case 0:
			// APNG_DISPOSE_OP_NONE: no disposal is done on this frame before rendering the next; the contents of the output buffer are left as is.
			//System.out.println("Frame "+currentFrame.sequenceNumber+" do nothing dispose");
			// do nothing
			break;
		
		case 1:
			// APNG_DISPOSE_OP_BACKGROUND: the frame's region of the output buffer is to be cleared to fully transparent black before rendering the next frame.
			//System.out.println(String.format("Frame %d clear background (full=%s, x=%d y=%d w=%d h=%d) previous=%s", currentFrame.sequenceNumber,
			//        isFull, currentFrame.xOffset, currentFrame.yOffset, currentFrame.width, currentFrame.height, previous));
			//if (true || isFull) {
			canvas.drawColor( 0, PorterDuff.Mode.CLEAR ); // Clear to fully transparent black
			//                } else {
			//                    Rect rt = new Rect(currentFrame.xOffset, currentFrame.yOffset, currentFrame.width+currentFrame.xOffset, currentFrame.height+currentFrame.yOffset);
			//                    paint = new Paint();
			//                    paint.setColor(0);
			//                    paint.setStyle(Paint.Style.FILL);
			//                    canvas.drawRect(rt, paint);
			//                }
			break;
		
		case 2:
			// APNG_DISPOSE_OP_PREVIOUS: the frame's region of the output buffer is to be reverted to the previous contents before rendering the next frame.
			//System.out.println(String.format("Frame %d restore previous (full=%s, x=%d y=%d w=%d h=%d) previous=%s", currentFrame.sequenceNumber,
			//        isFull, currentFrame.xOffset, currentFrame.yOffset, currentFrame.width, currentFrame.height, previous));
			// Put the original section back
			if( previous != null ){
				canvas.drawBitmap( previous, currentFrame.xOffset, currentFrame.yOffset, sSrcModePaint );
				previous.recycle();
			}
			break;
		}
		
		currentFrame = null;
	}
	
	///////////////////////////////////////////////////////////////
	
	// 再生速度の調整
	float durationScale = 1f;
	
	@SuppressWarnings("unused") public float getDurationScale(){
		return durationScale;
	}
	
	@SuppressWarnings("unused") public void setDurationScale( float durationScale ){
		this.durationScale = durationScale;
	}
	
	@SuppressWarnings("unused") public boolean isSingleFrame(){
		return animationControl == null || 1 == animationControl.numFrames;
	}
	
	@SuppressWarnings("unused") public int getNumFrames(){
		return animationControl == null ? 1 : animationControl.numFrames;
	}
	
	private static final long DELAY_AFTER_END = 3000L;
	
	public static class FindFrameResult {
		public Bitmap bitmap;
		public long delay; // 再描画が必要ない場合は Long.MAX_VALUE
	}
	
	// シーク位置に応じたコマ画像と次のコマまでの残り時間をresultに格納する
	public void findFrame( @NonNull FindFrameResult result, long t ){
		
		if( mBitmapNonAnimation != null ){
			result.bitmap = mBitmapNonAnimation;
			result.delay = Long.MAX_VALUE;
			return;
		}
		
		int frame_count = frames.size();
		
		boolean isFinite = ! animationControl.loopForever();
		int repeatSequenceCount = isFinite ? animationControl.numPlays : 1;
		long end_wait = ( isFinite ? DELAY_AFTER_END : 0L );
		long loop_total = ( time_total * repeatSequenceCount ) + end_wait;
		if( loop_total <= 0 ) loop_total = 1;
		
		long tf = (long) ( 0.5f + t < 0f ? 0f : t / durationScale );
		
		// 全体の繰り返し時刻で余りを計算
		long tl = tf % loop_total;
		if( tl >= loop_total - end_wait ){
			// 終端で待機状態
			result.bitmap = frames.get( frame_count - 1 ).bitmap;
			result.delay = (long) ( 0.5f + ( loop_total - tl ) * durationScale );
			return;
		}
		// １ループの繰り返し時刻で余りを計算
		long tt = tl % time_total;
		
		// フレームリストを時刻で二分探索
		int s = 0, e = frame_count;
		while( e - s > 1 ){
			int mid = ( s + e ) >> 1;
			Frame frame = frames.get( mid );
			// log.d("s=%d,m=%d,e=%d tt=%d,fs=%s,fe=%d",s,mid,e,tt,frame.time_start,frame.time_start+frame.time_width );
			if( tt < frame.time_start ){
				e = mid;
			}else if( tt >= frame.time_start + frame.time_width ){
				s = mid + 1;
			}else{
				s = mid;
				break;
			}
		}
		s = s < 0 ? 0 : s >= frame_count - 1 ? frame_count - 1 : s;
		Frame frame = frames.get( s );
		long delay = frame.time_start + frame.time_width - tt;
		result.bitmap = frames.get( s ).bitmap;
		result.delay = (long) ( 0.5f + durationScale * ( delay < 0f ? 0f : delay ) );
		
		// log.d("findFrame tf=%d,tl=%d/%d,tt=%d/%d,s=%d,w=%d,delay=%d",tf,tl,loop_total,tt,time_total,s,frame.time_width,result.delay);
	}
	
	/////////////////////////////////////////////////////////////////////
	
	// APNGのパース中に随時呼び出される
	static class APNGParseEventHandler extends BasicArgb8888Director< APNGFrames > {
		
		private final int size_max;
		
		// 作成
		APNGParseEventHandler( int size_max ){
			this.size_max = size_max;
		}
		
		private PngHeader header;
		
		// ヘッダが分かった
		@Override
		public void receiveHeader( PngHeader header, PngScanlineBuffer buffer ) throws PngException{
			this.header = header;
			
			// 親クラスのprotectedフィールドを更新する
			Argb8888Bitmap pngBitmap = new Argb8888Bitmap( header.width, header.height );
			this.scanlineProcessor = Argb8888Processors.from( header, buffer, pngBitmap );
		}
		
		// デフォルト画像の手前で呼ばれる
		@Override public Argb8888ScanlineProcessor beforeDefaultImage(){
			return scanlineProcessor;
		}
		
		// デフォルト画像が分かった
		// おそらく receiveAnimationControl より先に呼ばれる
		@Override public void receiveDefaultImage( Argb8888Bitmap defaultImage ){
			// japng ライブラリの返すデフォルトイメージはあまり信用できないので使わない
		}
		
		private APNGFrames mFrames = null;
		
		// アニメーション制御情報が分かった
		@Override
		public void receiveAnimationControl( PngAnimationControl animationControl ){
			this.mFrames = new APNGFrames( header, scanlineProcessor, animationControl, size_max );
		}
		
		boolean isAnimated(){
			return mFrames != null;
		}
		
		@Override public boolean wantDefaultImage(){
			return ! isAnimated();
		}
		
		@Override public boolean wantAnimationFrames(){
			return true; // isAnimated;
		}
		
		// フレーム制御情報が分かった
		@Override
		public Argb8888ScanlineProcessor receiveFrameControl( PngFrameControl frameControl ){
			if( ! isAnimated() ) throw new RuntimeException( "not animation image" );
			return mFrames.beginFrame( frameControl );
		}
		
		// フレーム画像が分かった
		@Override public void receiveFrameImage( Argb8888Bitmap frameImage ){
			if( ! isAnimated() ) throw new RuntimeException( "not animation image" );
			mFrames.completeFrame( frameImage );
		}
		
		// 結果を取得する
		@Override public APNGFrames getResult(){
			if( mFrames != null ){
				if( ! mFrames.isSingleFrame() ){
					return mFrames;
				}
				mFrames.dispose();
				mFrames = null;
			}
			return null;
		}
		
		// 処理中に例外が起きた場合、Bitmapリソースを解放する
		void dispose(){
			if( mFrames != null ){
				mFrames.dispose();
				mFrames = null;
			}
		}
	}
	
	/////////////////////////////////////////////////////////////////////
	
	// entry point is here
	@Nullable static APNGFrames parseAPNG( InputStream is, int size_max )
		throws PngException{
		APNGParseEventHandler handler = new APNGParseEventHandler( size_max );
		try{
			Argb8888Processor< APNGFrames > processor = new Argb8888Processor<>( handler );
			PngReader< APNGFrames > reader = new DefaultPngChunkReader<>( processor );
			APNGFrames result = PngReadHelper.read( is, reader );
			if( result != null ) result.onParseComplete();
			return result;
		}catch( Throwable ex ){
			handler.dispose();
			throw ex;
		}
	}
	
}

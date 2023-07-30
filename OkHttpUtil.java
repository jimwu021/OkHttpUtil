package okhttp.util;

import java.io.File;
import java.io.IOException;
import java.net.Proxy;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;

import okhttp3.Authenticator;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Route;
import okio.BufferedSource;
import okio.ByteString;

/**
 * 本公用類別提供便利的方法來使用OkHttp進行網路連線。 OkHttp是一個處理HTTP網路請求的開源函式庫，主要有以下幾個特點：
 * <ul>
 * <li>支援HTTP/2，讓所有針對同一主機的請求可以共享一個socket</li>
 * <li>連線池減少請求延遲(如果HTTP/2不能用的話)</li>
 * <li>Transparent GZIP 縮減了下載大小</li>
 * <li>回應快取完全避免了網路重複請求</li>
 * </ul>
 * 
 * <p>傳統HttpURLConnection只能發送同步請求(synchronous request)、不支援HTTP/2，並且缺乏壓縮和表單處理等附加功能。
 * 如今使用HttpURLConnection的主要優點是保證可用，即便在較舊的Java版本，且不需要添加額外的依賴。
 * 
 * <p>綜上所述，使用OkHttp應可得到比HttpURLConnection更佳的性能。為追求性能，本公用類別不寫log增加額外的IO開銷。
 * 請求url、請求參數、回傳結果呼叫端都知道，可以自己寫log，若程式出錯會拋出例外，例外訊息也有相應描述。
 *
 * <p>若要得到更完整的回應內容，可呼叫_ForResponseWrapper系列方法。此系列方法回傳<code>ResponseWrapper&lt;T&gt;</code>物件，
 * 可額外取得responseCode 與 responseMessage，並可用泛型指定responseData的資料型別。
 * 目前支援String, byte[], InputStream與ByteString。除以上類別之外，即便連線成功，responseData也只會回傳null。
 * 日後若要增加其他回應內容， 應參考Response、ResponseBody類別提供的方法。
 *
 * <p>sendPost系列方法的部分參數說明請參考{@code sendPost()}方法。
 * 
 * <p>呼叫範例：
 * <pre>
 * 發起POST請求，request body為JSON格式，預計目標API回傳內容為字串：{@code 
 * OkHttpUtil.sendPostByJson(API_URL, null, reqJson, String.class);
 * } </pre>
 * 
 * <pre>
 * 使用proxy發起POST請求，request body為JSON格式，指定timeout時間30秒，預計目標API回傳內容為字串：
 * {@code
 * Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(PROXY_URL, 8080));
 * OkHttpUtil.sendPostByJson(API_URL, null, reqJson, String.class, 30 * 1000, proxy, "PROXY_ACC", "PROXY_PWD");
 * } </pre>
 * 
 * <pre>
 * 發起POST請求，request body為form表單，指定request headers，預計目標API回傳內容為位元組陣列，要取得response code(http code) 和 response message：
 * <code>
 * OkHttpUtil.ResponseWrapper&lt;byte[]&gt; wrapper = new OkHttpUtil.ResponseWrapper<>();
 * <b>wrapper = OkHttpUtil.sendPostByFormForResponseWrapper(API_URL, headers, reqForm, byte[].class);</b>
 * int responseCode = wrapper.getResponseCode();  // 200
 * String responseMessage = wrapper.getResponseMessage(); // OK
 * byte[] responseData = wrapper.getResponseData();
 * </code></pre>
 * 
 * <p>注意事項：
 * <ul>
 * 	<li>除了private方法以外，不直接回傳Response：因為Response與ResponseBody用完需要被關閉，如果直接回傳，關閉的責任在呼叫者上，不確定性太高。</li>
 * </ul>
 * 
 * @see https://square.github.io/okhttp/
 * @author Jim Wu
 */
public class OkHttpUtil {

	/**
	 * 整個專案應該只有一個new OkHttpClient()，保持Singleton。
	 * 如果不使用本類別的方法而要自行撰寫OkHttp連線，
	 * 應使用okHttpClient此變數，透過okHttpClient.newBuilder()的方式，
	 * 自定義和okHttpClient共享相同設定的client來做連線。
	 * 詳見下面官方說明。
	 * 
	 * <h2>官方API文件建議：</h2>
	 * <h3>1. OkHttpClient 應該被共享</h3>
	 * 當你創建一個單獨的OkHttpClient實例並且在所有的HTTP呼叫(call)重複使用它，OkHttp性能最佳。
	 * 因為每一個client都有自己的連線池與執行緒池。
	 * 重複使用連線(connection)和執行緒可以減少延遲(latency)並節省記憶體。
	 * 相反地，為每個請求(request)創建一個client浪費了閒置池裡的資源。
	 * 
	 * <h3>2. 用newBuilder()自定義你的Client</h3>
	 * 你可以用newBuilder()自定義一個共享的OkHttpClient實例。
	 * 這會建構一個共享相同連線池，執行緒池和配置(configuration)的client。
	 * 使用builder方法將配置添加到衍生的client以用於特定目的。
	 * 以下範例展示了一個有500毫秒read timeout和1000毫秒write timeout的呼叫。
	 * 保留了原始配置，但可以覆寫。
	 * <pre><code>
	 * OkHttpClient client = okHttpClient.newBuilder()
	 * 	.readTimeout(500, TimeUnit.MILLISECONDS)
	 * 	.writeTimeout(1000, TimeUnit.MILLISECONDS)
	 * 	.build();
	 * Response response = client.newCall(request).execute();
	 * </code></pre>
	 * 
	 * <h3><strong>3. 不需要關閉(資源)</strong></h3>
	 * 被持有的執行緒和連線如果持續閒置將被自動釋放。
	 * 但如果你在寫一個需要積極釋放未使用資源的應用程式，你也可以這樣做。
	 * 
	 * @see https://square.github.io/okhttp/4.x/okhttp/okhttp3/-ok-http-client/
	 */
	public static final OkHttpClient okHttpClient = new OkHttpClient();
	
	/**
	 * sendPost系列方法的核心。
	 * 直接回傳Response。為避免呼叫者沒有關閉Response資源，因此僅供Util內部呼叫。
	 * 
	 * @param url
	 * @param mediaType	媒體類型
	 * @param headers	如果mediaType非空值，則會根據mediaType參數設定Content-Type header。如果呼叫者自行傳入headers，不需額外傳入Content-Type header。
	 * @param body		可依據不同mediaType傳入對應的物件。只能是String、byte[]、File、ByteString的其中一種
	 * @param timeout	單位是毫秒。-1: 使用OkHttpClient預設值(10秒)。 0: no timeout
	 * @param proxy		範例：new Proxy(Proxy.Type.HTTP, new InetSocketAddress(PROXY_STR, 8080));
	 * @param proxyUsername
	 * @param proxyPassword
	 * @return Response <strong>呼叫端需負責呼叫close()方法關閉資源！否則將消耗大量記憶體</strong>
	 * @throws IOException
	 */
	private static Response sendPost(String url, String mediaType, Map<String, String> headers, Object body, int timeout, Proxy proxy, String proxyUsername, String proxyPassword) throws IOException {
		if (StringUtils.isBlank(url))	throw new IllegalArgumentException("url is blank");
		if (body == null)				throw new IllegalArgumentException("The POST request must have a body.");
		if (timeout < -1)				throw new IllegalArgumentException("illegal timeout");
		
		MediaType mt = MediaType.parse(mediaType);
		RequestBody requestBody = null;
		
		if (body instanceof String)
			requestBody = RequestBody.create(mt, (String)body);
		else if (body instanceof byte[])
			requestBody = RequestBody.create(mt, (byte[])body);
		else if (body instanceof File)
			requestBody = RequestBody.create(mt, (File)body);
		else if (body instanceof ByteString)
			requestBody = RequestBody.create(mt, (ByteString)body);
		else
			throw new IllegalArgumentException("illegal param type:" + body.getClass());
		
		OkHttpClient.Builder clientBuilder = okHttpClient.newBuilder();
		Request.Builder requestBuilder = new Request.Builder();
		
		if (timeout != -1) {
			clientBuilder.connectTimeout(timeout, TimeUnit.MILLISECONDS)
				.writeTimeout(timeout, TimeUnit.MILLISECONDS)
				.readTimeout(timeout, TimeUnit.MILLISECONDS);
		}
		
		if (proxy != null) {
			clientBuilder.proxy(proxy);
			if (proxyUsername != null && proxyPassword != null) {
				Authenticator proxyAuthenticator = new Authenticator() {
					@Override
					public Request authenticate(Route route, Response response) throws IOException {
						return response.request().newBuilder()
								.header("Proxy-Authorization", Credentials.basic(proxyUsername, proxyPassword))
								.build();
					}
				};
				clientBuilder.proxyAuthenticator(proxyAuthenticator);
			}
		}
		
		if (headers != null) {
			for (String key : headers.keySet()) {
				requestBuilder.addHeader(key, headers.get(key));
			}
		}
		
		if (mediaType != null) {
			requestBuilder.addHeader("Content-Type", mediaType);
		}
		
		OkHttpClient client = clientBuilder.build();
		Request request = requestBuilder.url(url)
					.post(requestBody)
					.build();
		
		return client.newCall(request).execute();
	}

	
	// sendPostByJson
	public static <T> T sendPostByJson(String url, Map<String, String> headers, String json, Class<T> responseType) throws IOException {
		return sendPostByJson(url, headers, json, responseType, -1, null, null, null);
	}
	
	public static <T> T sendPostByJson(String url, Map<String, String> headers, String json, Class<T> responseType, int timeout) throws IOException {
		return sendPostByJson(url, headers, json, responseType, timeout, null, null, null);
	}
	
	public static <T> T sendPostByJson(String url, Map<String, String> headers, String json, Class<T> responseType, int timeout, Proxy proxy, String proxyUsername, String proxyPassword) throws IOException {
		// try-with-resources 語法確保資源有被關閉
		try (Response response = sendPost(url, "application/json", headers, json, timeout, proxy, proxyUsername, proxyPassword); 
				BufferedSource source = response.body().source()) {
			return getMethodReturnValueByResponseType(responseType, source);
		}
	}
	
	
	// sendPostByJsonForResponseWrapper
	public static <T> ResponseWrapper<T> sendPostByJsonForResponseWrapper(String url, Map<String, String> headers, String json, Class<T> responseType) throws IOException {
		return sendPostByJsonForResponseWrapper(url, headers, json, responseType, -1, null, null, null);
	}
	
	public static <T> ResponseWrapper<T> sendPostByJsonForResponseWrapper(String url, Map<String, String> headers, String json, Class<T> responseType, int timeout) throws IOException {
		return sendPostByJsonForResponseWrapper(url, headers, json, responseType, timeout, null, null, null);
	}
	
	public static <T> ResponseWrapper<T> sendPostByJsonForResponseWrapper(String url, Map<String, String> headers, String json, Class<T> responseType, int timeout, Proxy proxy, String proxyUsername, String proxyPassword) throws IOException {
		ResponseWrapper<T> wrapper = new ResponseWrapper<>();
		// try-with-resources 語法確保資源有被關閉
		try (Response response = sendPost(url, "application/json", headers, json, timeout, proxy, proxyUsername, proxyPassword); 
				BufferedSource source = response.body().source()) {
			wrapper.setResponseCode(response.code());
			wrapper.setResponseMessage(response.message());
			wrapper.setResponseData(getMethodReturnValueByResponseType(responseType, source));
		}
		return wrapper;
	}
	
	
	// sendPostByForm
	public static <T> T sendPostByForm(String url, Map<String, String> headers, String form, Class<T> responseType) throws IOException {
		return sendPostByForm(url, headers, form, responseType, -1, null, null, null);
	}
	
	public static <T> T sendPostByForm(String url, Map<String, String> headers, String form, Class<T> responseType, int timeout) throws IOException {
		return sendPostByForm(url, headers, form, responseType, timeout, null, null, null);
	}
	
	public static <T> T sendPostByForm(String url, Map<String, String> headers, String form, Class<T> responseType, int timeout, Proxy proxy, String proxyUsername, String proxyPassword) throws IOException {
		// try-with-resources 語法確保資源有被關閉
		try (Response response = sendPost(url, "application/x-www-form-urlencoded", headers, form, timeout, proxy, proxyUsername, proxyPassword); 
				BufferedSource source = response.body().source()) {
			return getMethodReturnValueByResponseType(responseType, source);
		}
	}
	

	// sendPostByFormForResponseWrapper
	public static <T> ResponseWrapper<T> sendPostByFormForResponseWrapper(String url, Map<String, String> headers, String form, Class<T> responseType) throws IOException {
		return sendPostByFormForResponseWrapper(url, headers, form, responseType, -1, null, null, null);
	}
	
	public static <T> ResponseWrapper<T> sendPostByFormForResponseWrapper(String url, Map<String, String> headers, String form, Class<T> responseType, int timeout) throws IOException {
		return sendPostByFormForResponseWrapper(url, headers, form, responseType, timeout, null, null, null);
	}
	
	public static <T> ResponseWrapper<T> sendPostByFormForResponseWrapper(String url, Map<String, String> headers, String form, Class<T> responseType, int timeout, Proxy proxy, String proxyUsername, String proxyPassword) throws IOException {
		ResponseWrapper<T> wrapper = new ResponseWrapper<>();
		// try-with-resources 語法確保資源有被關閉
		try (Response response = sendPost(url, "application/x-www-form-urlencoded", headers, form, timeout, proxy, proxyUsername, proxyPassword); 
				BufferedSource source = response.body().source()) {
			wrapper.setResponseCode(response.code());
			wrapper.setResponseMessage(response.message());
			wrapper.setResponseData(getMethodReturnValueByResponseType(responseType, source));
		}
		return wrapper;
	}
	
	
	/**
	 * 根據傳入的responseType參數取得方法回傳值。
	 * 
	 * @param <T>
	 * @param responseType 目前支持String, byte[], InputStream與 ByteString，若要擴充應參考BufferedSource類別提供的方法。
	 * @param source
	 * @return 根據傳入的responseType回傳對應的泛型物件，如果無法對應則回傳null。
	 * @throws IOException
	 */
	@SuppressWarnings("unchecked")
	private static <T> T getMethodReturnValueByResponseType(Class<T> responseType, BufferedSource source) throws IOException {
		switch (responseType.getTypeName()) {
		case "java.lang.String":
			return (T) source.readUtf8();
		case "byte[]":
			return (T) source.readByteArray();
		case "java.io.InputStream":
			return (T) source.inputStream();
		case "okio.ByteString":
			return (T) source.readByteString();
		}
		return null;
	}
	
	
	/**
	 * 本類別把從OkHttp連線取得的回應內容包裹在一個物件中。
	 * 日後若要增加其他回應內容，應參考Response、ResponseBody類別提供的方法。
	 *  
	 * @author Jim Wu
	 *
	 * @param <T> 可用泛型指定responseData的資料型別。
	 */
	public static class ResponseWrapper<T> {
		/**
		 * HTTP status code
		 */
		private int responseCode;
		/**
		 * HTTP status message
		 */
		private String responseMessage;
		
		private T responseData;
		
		public int getResponseCode() {
			return responseCode;
		}
		public void setResponseCode(int responseCode) {
			this.responseCode = responseCode;
		}
		public String getResponseMessage() {
			return responseMessage;
		}
		public void setResponseMessage(String responseMessage) {
			this.responseMessage = responseMessage;
		}
		public T getResponseData() {
			return responseData;
		}
		public void setResponseData(T responseData) {
			this.responseData = responseData;
		}
		
	}
	
}

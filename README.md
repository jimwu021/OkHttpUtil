# OkHttpUtil
本公用類別提供便利的方法來使用OkHttp進行網路連線。 OkHttp是一個處理HTTP網路請求的開源函式庫，主要有以下幾個特點：
<ul>
<li>支援HTTP/2，讓所有針對同一主機的請求可以共享一個socket</li>
<li>連線池減少請求延遲(如果HTTP/2不能用的話)</li>
<li>Transparent GZIP 縮減了下載大小</li>
<li>回應快取完全避免了網路重複請求</li>
</ul>

<p>傳統HttpURLConnection只能發送同步請求(synchronous request)、不支援HTTP/2，並且缺乏壓縮和表單處理等附加功能。
如今使用HttpURLConnection的主要優點是保證可用，即便在較舊的Java版本，且不需要添加額外的依賴。

<p>綜上所述，使用OkHttp應可得到比HttpURLConnection更佳的性能。為追求性能，本公用類別不寫log增加額外的IO開銷。
請求url、請求參數、回傳結果呼叫端都知道，可以自己寫log，若程式出錯會拋出例外，例外訊息也有相應描述。

<p>若要得到更完整的回應內容，可呼叫_ForResponseWrapper系列方法。此系列方法回傳<code>ResponseWrapper&lt;T&gt;</code>物件，
可額外取得responseCode 與 responseMessage，並可用泛型指定responseData的資料型別。
目前支援String, byte[], InputStream與ByteString。除以上類別之外，即便連線成功，responseData也只會回傳null。
日後若要增加其他回應內容， 應參考Response、ResponseBody類別提供的方法。

<p>sendPost系列方法的部分參數說明請參考<code>sendPost()</code>方法。

## 呼叫範例：
<pre>
發起POST請求，request body為JSON格式，預計目標API回傳內容為字串：
OkHttpUtil.sendPostByJson(API_URL, null, reqJson, String.class);
</pre>

<pre>
使用proxy發起POST請求，request body為JSON格式，指定timeout時間30秒，預計目標API回傳內容為字串：
Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(PROXY_URL, 8080));
OkHttpUtil.sendPostByJson(API_URL, null, reqJson, String.class, 30 * 1000, proxy, "PROXY_ACC", "PROXY_PWD");
</pre>

<pre>
發起POST請求，request body為form表單，指定request headers，預計目標API回傳內容為位元組陣列，要取得response code(http code) 和 response message：
<code>
OkHttpUtil.ResponseWrapper&lt;byte[]&gt; wrapper = new OkHttpUtil.ResponseWrapper<>();
<b>wrapper = OkHttpUtil.sendPostByFormForResponseWrapper(API_URL, headers, reqForm, byte[].class);</b>
int responseCode = wrapper.getResponseCode();  // 200
String responseMessage = wrapper.getResponseMessage(); // OK
byte[] responseData = wrapper.getResponseData();
</code></pre>

## 注意事項：
<ul>
	<li>除了private方法以外，不直接回傳Response：因為Response與ResponseBody用完需要被關閉，如果直接回傳，關閉的責任在呼叫者上，不確定性太高。</li>
</ul>

## Dependencies: 
  okhttp第四版開始改用kotlin撰寫，為了導入到java專案所以還是選擇第三版，方便追蹤程式碼。okio則選擇能與okhttp第三版配合的版本。以下是撰寫本專案時採用的版本：
<ul>
  <li>okhttp-3.14.9.jar</li>
  <li>okio-1.17.5.jar</li>
</ul>

@see https://square.github.io/okhttp/ <br>
@author Jim Wu

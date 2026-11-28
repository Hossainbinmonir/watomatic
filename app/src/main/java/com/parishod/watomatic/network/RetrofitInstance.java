package com.parishod.watomatic.network;

import androidx.annotation.VisibleForTesting;

import com.parishod.watomatic.network.model.atomatic.AtomaticAIErrorResponse;
import com.parishod.watomatic.network.model.openai.OpenAIErrorResponse;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.ResponseBody;
import retrofit2.Converter;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;
// retrofit2.Response will be used with FQN

public class RetrofitInstance {
    private static final int MAX_CUSTOM_AI_RETROFIT_CACHE_SIZE = 16;
    private static volatile Retrofit retrofit;
    private static volatile Retrofit openAIRetrofit;
    private static volatile Retrofit atomaticAIRetrofit;
    private static volatile OkHttpClient sharedGithubOkHttpClient;
    private static volatile OkHttpClient sharedAiOkHttpClient;
    private static final Object GITHUB_LOCK = new Object();
    private static final Object AI_LOCK = new Object();
    private static final Map<String, Retrofit> customOpenAIRetrofitCache =
            Collections.synchronizedMap(new LinkedHashMap<String, Retrofit>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Retrofit> eldest) {
                    return size() > MAX_CUSTOM_AI_RETROFIT_CACHE_SIZE;
                }
            });

    private static final String BASE_URL = "https://api.github.com";
    public static final String OPENAI_BASE_URL = "https://api.openai.com/";
    public static final String ATOMATIC_AI_BASE_URL = "https://tsmnguqqaeot7avq35i7nbxch40wuowe.lambda-url.us-east-1.on.aws/";

    private static OkHttpClient getSharedGithubOkHttpClient() {
        OkHttpClient client = sharedGithubOkHttpClient;
        if (client == null) {
            synchronized (GITHUB_LOCK) {
                client = sharedGithubOkHttpClient;
                if (client == null) {
                    client = new OkHttpClient.Builder().build();
                    sharedGithubOkHttpClient = client;
                }
            }
        }
        return client;
    }

    @VisibleForTesting
    public static OkHttpClient getSharedAiOkHttpClient() {
        OkHttpClient client = sharedAiOkHttpClient;
        if (client == null) {
            synchronized (AI_LOCK) {
                client = sharedAiOkHttpClient;
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(30, TimeUnit.SECONDS)
                            .readTimeout(40, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS)
                            .callTimeout(45, TimeUnit.SECONDS)
                            // AI completion POSTs are non-idempotent: never silently replay on socket failure.
                            .retryOnConnectionFailure(false)
                            .build();
                    sharedAiOkHttpClient = client;
                }
            }
        }
        return client;
    }

    public static Retrofit getRetrofitInstance() {
        Retrofit current = retrofit;
        if (current == null) {
            synchronized (GITHUB_LOCK) {
                current = retrofit;
                if (current == null) {
                    current = new retrofit2.Retrofit.Builder()
                            .baseUrl(BASE_URL)
                            .addConverterFactory(ScalarsConverterFactory.create())
                            .addConverterFactory(GsonConverterFactory.create())
                            .client(getSharedGithubOkHttpClient())
                            .build();
                    retrofit = current;
                }
            }
        }
        return current;
    }

    public static Retrofit getOpenAIRetrofitInstance(String baseUrl) {
        if (baseUrl == null) {
            return createOpenAIRetrofitInstance(null);
        }
        String normalizedKey = baseUrl.trim();
        if (OPENAI_BASE_URL.equals(normalizedKey)) {
            return getOpenAIRetrofitInstance();
        }
        Retrofit cached = customOpenAIRetrofitCache.get(normalizedKey);
        if (cached != null) {
            return cached;
        }
        synchronized (AI_LOCK) {
            cached = customOpenAIRetrofitCache.get(normalizedKey);
            if (cached == null) {
                cached = createOpenAIRetrofitInstance(normalizedKey);
                customOpenAIRetrofitCache.put(normalizedKey, cached);
            }
            return cached;
        }
    }

    public static Retrofit getOpenAIRetrofitInstance() {
        Retrofit current = openAIRetrofit;
        if (current == null) {
            synchronized (AI_LOCK) {
                current = openAIRetrofit;
                if (current == null) {
                    current = createOpenAIRetrofitInstance(OPENAI_BASE_URL);
                    openAIRetrofit = current;
                }
            }
        }
        return current;
    }

    private static Retrofit createOpenAIRetrofitInstance(String baseUrl) {
        return new Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create()) // Only Gson for OpenAI
                .client(getSharedAiOkHttpClient())
                .build();
    }

    public static OpenAIErrorResponse parseOpenAIError(retrofit2.Response<?> response) {
        if (response.errorBody() == null) {
            return null;
        }

        // Ensure you use the Retrofit instance configured for OpenAI
        // (the one that knows about OpenAIErrorResponse and GsonConverterFactory)
        Retrofit currentOpenAIRetrofit = getOpenAIRetrofitInstance();
        if (currentOpenAIRetrofit == null) {
            // This shouldn't happen if getOpenAIRetrofitInstance is called correctly before this
            // but as a safeguard:
            android.util.Log.e("RetrofitInstance", "OpenAI Retrofit instance is null, cannot parse error.");
            return null;
        }

        Converter<ResponseBody, OpenAIErrorResponse> converter =
                currentOpenAIRetrofit.responseBodyConverter(OpenAIErrorResponse.class, new Annotation[0]);

        try {
            return converter.convert(response.errorBody());
        } catch (IOException e) {
            // Log error class only without exception message or payload
            android.util.Log.e("RetrofitInstance", "IOException parsing OpenAI error response (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    public static Retrofit getAtomaticAIRetrofitInstance() {
        Retrofit current = atomaticAIRetrofit;
        if (current == null) {
            synchronized (AI_LOCK) {
                current = atomaticAIRetrofit;
                if (current == null) {
                    current = new Retrofit.Builder()
                            .baseUrl(ATOMATIC_AI_BASE_URL)
                            .addConverterFactory(GsonConverterFactory.create())
                            .client(getSharedAiOkHttpClient())
                            .build();
                    atomaticAIRetrofit = current;
                }
            }
        }
        return current;
    }

    public static AtomaticAIErrorResponse parseAtomaticAIError(retrofit2.Response<?> response) {
        if (response.errorBody() == null) {
            return null;
        }

        Retrofit currentAtomaticAIRetrofit = getAtomaticAIRetrofitInstance();
        if (currentAtomaticAIRetrofit == null) {
            android.util.Log.e("RetrofitInstance", "Atomatic AI Retrofit instance is null, cannot parse error.");
            return null;
        }

        Converter<ResponseBody, AtomaticAIErrorResponse> converter =
                currentAtomaticAIRetrofit.responseBodyConverter(AtomaticAIErrorResponse.class, new Annotation[0]);

        try {
            return converter.convert(response.errorBody());
        } catch (IOException e) {
            android.util.Log.e("RetrofitInstance", "IOException parsing Atomatic AI error response (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }
}

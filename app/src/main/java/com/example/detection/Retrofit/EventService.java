package com.example.detection.Retrofit;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Headers;
import retrofit2.http.POST;

public interface EventService {
    @Headers("Content-Type: application/json")
    @POST("api/Event/Create")
    Call<String> sendEvent(@Body Event event);
}

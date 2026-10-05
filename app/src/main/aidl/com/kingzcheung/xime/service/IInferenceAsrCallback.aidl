package com.kingzcheung.xime.service;

oneway interface IInferenceAsrCallback {
    void onPartialResult(String text);
    void onFinalResult(String text);
    void onError(String message);
}

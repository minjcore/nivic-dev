package main

import (
	"encoding/json"
	"net/http"
)

func routes(store *Store, authURL, wireAddr, staticDir, merchantsURL, floatPwd string) http.Handler {
	mux := http.NewServeMux()
	addWebRoutes(mux, authURL, wireAddr, staticDir, floatPwd)
	addMerchantsProxy(mux, merchantsURL)
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	mux.HandleFunc("POST /tokens", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			UID      uint32 `json:"uid"`
			Platform string `json:"platform"`
			Token    string `json:"token"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		if req.UID == 0 || (req.Platform != "ios" && req.Platform != "android") || req.Token == "" {
			http.Error(w, "uid, platform (ios|android), token required", http.StatusBadRequest)
			return
		}
		if err := store.Register(req.UID, req.Platform, req.Token); err != nil {
			http.Error(w, "store error", http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})
	return mux
}

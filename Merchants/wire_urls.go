package main

import (
	"fmt"
	"net/url"
)

// wireStoreURL opens Mc mini-app in Wire (no order yet).
func wireStoreURL(mid uint32) string {
	return fmt.Sprintf("saving://store?mid=%d", mid)
}

// wireIntentURL opens FrontStore with a pending order (rid must exist on Wire Server).
func wireIntentURL(mid uint32, requestID uint64, amount uint64, orderID string) string {
	u := fmt.Sprintf("saving://intent?mid=%d&rid=%d&amount=%d", mid, requestID, amount)
	if orderID != "" {
		u += "&oid=" + url.QueryEscape(orderID)
	}
	return u
}

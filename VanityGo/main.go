package main

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
)

// pkg describes a Go package hosted under go.nivic.dev.
type pkg struct {
	vcs    string // "git"
	remote string // actual git clone URL
	docs   string // godoc/source root (optional, falls back to remote)
}

// packages is the canonical registry of go.nivic.dev import paths.
// key = path segment after go.nivic.dev/ (no trailing slash)
var packages = map[string]pkg{
	"goobject": {
		vcs:    "git",
		remote: "https://github.com/minjcore/goobject",
	},
	// Future: "fluxor", "wire-sdk", etc.
}

func main() {
	addr := os.Getenv("VANITY_ADDR")
	if addr == "" {
		addr = ":8086"
	}
	log.Printf("go.nivic.dev vanity server on %s", addr)
	http.HandleFunc("/", handle)
	if err := http.ListenAndServe(addr, nil); err != nil {
		log.Fatal(err)
	}
}

func handle(w http.ResponseWriter, r *http.Request) {
	// Extract top-level package name from path
	path := strings.TrimPrefix(r.URL.Path, "/")
	root := strings.SplitN(path, "/", 2)[0]

	p, ok := packages[root]
	if root == "" {
		// Index page
		serveIndex(w)
		return
	}
	if !ok {
		http.NotFound(w, r)
		return
	}

	importPath := "go.nivic.dev/" + root
	docs := p.docs
	if docs == "" {
		docs = p.remote
	}

	if r.FormValue("go-get") == "1" {
		// go tool fetches this to discover the VCS root
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		fmt.Fprintf(w, `<!DOCTYPE html><html><head>
<meta name="go-import" content="%s %s %s">
<meta name="go-source" content="%s _ %s/tree/main{/dir} %s/blob/main{/dir}/{file}#L{line}">
</head><body>
<p>go get %s</p>
<p><a href="%s">Source</a></p>
</body></html>`,
			importPath, p.vcs, p.remote,
			importPath, docs, docs,
			importPath, p.remote,
		)
		return
	}

	// Humans → redirect to source
	http.Redirect(w, r, p.remote, http.StatusFound)
}

func serveIndex(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprint(w, `<!DOCTYPE html><html><head>
<meta charset="utf-8">
<title>go.nivic.dev</title>
<style>
body{font-family:monospace;background:#0d1117;color:#c9d1d9;padding:40px;max-width:600px}
h1{color:#58a6ff}a{color:#79c0ff}code{background:#161b22;padding:2px 6px;border-radius:4px}
li{margin:8px 0}
</style>
</head><body>
<h1>go.nivic.dev</h1>
<p>Go packages by <a href="https://github.com/khangpc">@khangpc</a></p>
<ul>
`)
	for name, p := range packages {
		fmt.Fprintf(w, `<li><code>go get go.nivic.dev/%s</code> — <a href="%s">%s</a></li>`,
			name, p.remote, p.remote)
	}
	fmt.Fprint(w, `</ul></body></html>`)
}

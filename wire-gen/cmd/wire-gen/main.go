package main

import (
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/nivic/wire-gen/codegen"
	"github.com/nivic/wire-gen/schema"
)

func main() {
	schemaPath := flag.String("schema", "", "path to .wproto schema file (required)")
	targets := flag.String("target", "c,java,swift,kotlin,go", "comma-separated targets: c,java,swift,kotlin,go")
	outDir := flag.String("out", ".", "output directory")
	pkg := flag.String("pkg", "", "package/namespace for Java/Kotlin/Go (optional)")
	flag.Parse()

	if *schemaPath == "" {
		fmt.Fprintln(os.Stderr, "wire-gen: -schema is required")
		flag.Usage()
		os.Exit(1)
	}

	f, err := os.Open(*schemaPath)
	if err != nil {
		fatalf("open schema: %v", err)
	}
	defer f.Close()

	proto, err := schema.Parse(f)
	if err != nil {
		fatalf("parse schema: %v", err)
	}

	if err := os.MkdirAll(*outDir, 0o755); err != nil {
		fatalf("mkdir: %v", err)
	}

	for _, target := range strings.Split(*targets, ",") {
		target = strings.TrimSpace(target)
		switch target {
		case "c":
			out := filepath.Join(*outDir, strings.ToLower(proto.Name)+"_codec.h")
			generate(out, func(w *os.File) error { return codegen.GenerateC(proto, w) })
		case "java":
			name := proto.Name + "Codec.java"
			out := filepath.Join(*outDir, name)
			generate(out, func(w *os.File) error { return codegen.GenerateJava(proto, *pkg, w) })
		case "swift":
			out := filepath.Join(*outDir, proto.Name+".swift")
			generate(out, func(w *os.File) error { return codegen.GenerateSwift(proto, w) })
		case "kotlin":
			out := filepath.Join(*outDir, proto.Name+".kt")
			generate(out, func(w *os.File) error { return codegen.GenerateKotlin(proto, *pkg, w) })
		case "go":
			out := filepath.Join(*outDir, strings.ToLower(proto.Name)+"_codec.go")
			generate(out, func(w *os.File) error { return codegen.GenerateGo(proto, *pkg, w) })
		default:
			fmt.Fprintf(os.Stderr, "wire-gen: unknown target %q (valid: c,java,swift,kotlin,go)\n", target)
			os.Exit(1)
		}
	}
}

func generate(path string, fn func(*os.File) error) {
	f, err := os.Create(path)
	if err != nil {
		fatalf("create %s: %v", path, err)
	}
	defer f.Close()
	if err := fn(f); err != nil {
		fatalf("generate %s: %v", path, err)
	}
	fmt.Printf("wrote %s\n", path)
}

func fatalf(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "wire-gen: "+format+"\n", args...)
	os.Exit(1)
}

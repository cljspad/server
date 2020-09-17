# cljsfiddle server

The server that hosts [cljsfiddle](https://github.com/cljsfiddle/cljsfiddle)

## Running locally 

### 1) Compile and watch cljsfiddle UI code 

```bash
git clone git@github.com:cljsfiddle/cljsfiddle.git
clj -m cljs.main -co build.dev.edn -c -r -w
```

### 2) Run server code
```bash 
git clone git@github.com:cljsfiddle/server.git
lein repl
;; user=> (user/start! "/path/to/cljsfiddle/ui/code")
```

### 3) Open UI

```bash 
open http://localhost:3000
```

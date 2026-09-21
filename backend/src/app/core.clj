(ns app.core
  (:gen-class)
  (:require [clojure.string :as str]
            [ring.adapter.jetty :as jetty]
            [reitit.ring :as ring]
            [cheshire.core :as json]
            [app.db :as db]
            [app.flags :as flags]))

;; Estado de falha proposital para simulação de Canary / Rollback
(def falha-injetada? (atom false))
(def pronto? (atom false))
(def tempo-inicio (System/currentTimeMillis))

(def ^:private tamanho-max-titulo 255)

(defn obter-hostname []
  (try
    (.getHostName (java.net.InetAddress/getLocalHost))
    (catch Exception _ "unknown-host")))

(defn resposta-json
  ([dados] (resposta-json dados 200))
  ([dados status]
   {:status status
    :headers {"Content-Type" "application/json; charset=utf-8"
              "Access-Control-Allow-Origin" "*"
              "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
              "Access-Control-Allow-Headers" "Content-Type, Authorization"}
    :body (json/generate-string dados)}))

(def ^:private corpo-invalido ::corpo-invalido)

(defn- analisar-corpo-json
  "Retorna o corpo JSON como mapa, nil quando não há corpo, ou ::corpo-invalido
  quando o JSON é inválido — erro do cliente, não do servidor."
  [req]
  (if-let [body (:body req)]
    (let [bruto (slurp body)]
      (when-not (str/blank? bruto)
        (try
          (let [analisado (json/parse-string bruto true)]
            (if (map? analisado) analisado corpo-invalido))
          (catch Exception _ corpo-invalido))))
    nil))

(def ^:private limite-degradado 5)

(defn- comportamento-ativo
  "Efeito de cada feature flag na resposta da API. Permite demonstrar
  que o comportamento muda dinamicamente sem necessidade de novo deploy."
  []
  {:checkout (if (flags/habilitada? "new_checkout") "em-etapas" "classico")
   :layout   (if (flags/habilitada? "modern_layout") "moderno" "classico")
   :discount (if (flags/habilitada? "vip_discount") 20 0)
   :degraded (flags/habilitada? "ops_degraded_mode")})

;; Handlers (Tratadores de Requisição)
(defn tratar-versao [_req]
  (if @falha-injetada?
    (resposta-json {:status "error"
                    :error "Falha proposital ativada para simulação de Canary!"
                    :version (or (System/getenv "APP_VERSION") "v1.0.0")
                    :color (or (System/getenv "APP_COLOR") "blue")
                    :hostname (obter-hostname)}
                   500)
    (resposta-json {:version   (or (System/getenv "APP_VERSION") "v1.0.0")
                    :color     (or (System/getenv "APP_COLOR") "blue")
                    :hostname  (obter-hostname)
                    :uptime_ms (- (System/currentTimeMillis) tempo-inicio)
                    :timestamp (str (java.time.Instant/now))
                    :behaviour (comportamento-ativo)})))

(defn tratar-saude [_req]
  (if @falha-injetada?
    (resposta-json {:status "DOWN" :error "Instância com falha injetada"} 500)
    (resposta-json {:status "UP"
                    :version (or (System/getenv "APP_VERSION") "v1.0.0")
                    :color (or (System/getenv "APP_COLOR") "blue")
                    :hostname (obter-hostname)})))

(defn tratar-prontidao [_req]
  ;; Readiness = "já posso receber tráfego?". Enquanto o banco não responde, a
  ;; instância fica em 503 e o rolling update não a promove.
  (if @pronto?
    (resposta-json {:status "READY" :database "up" :hostname (obter-hostname)})
    (resposta-json {:status "NOT_READY" :database "connecting" :hostname (obter-hostname)} 503)))

(defn tratar-alternar-falha [req]
  (try
    (let [analisado (analisar-corpo-json req)
          _ (when (= analisado corpo-invalido)
              (throw (ex-info "JSON inválido" {})))
          desejado (:enabled analisado)
          novo-valor (if (nil? desejado)
                       (swap! falha-injetada? not)
                       (reset! falha-injetada? (boolean desejado)))]
      (resposta-json {:fault_injected novo-valor
                      :hostname (obter-hostname)
                      :message (if novo-valor
                                 "Falha simulada ATIVADA nesta instância."
                                 "Falha simulada DESATIVADA nesta instância.")}))
    (catch Exception e
      (resposta-json {:error (str "Corpo inválido: " (.getMessage e))} 400))))

(defn tratar-listar-itens [_req]
  ;; Ops Toggle: em modo degradado a consulta é encurtada para aliviar o banco.
  (let [degradado? (flags/habilitada? "ops_degraded_mode")
        limite     (if degradado? limite-degradado db/limite-padrao)]
    (resposta-json (cond-> {:items (db/listar-itens limite)
                            :total (db/contar-itens)
                            :limit limite
                            :degraded degradado?}
                     (flags/habilitada? "vip_discount") (assoc :discount_percent 20)))))

(defn tratar-criar-item [req]
  (try
    (let [analisado (analisar-corpo-json req)
          titulo-bruto (:title analisado)]
      (cond
        (= analisado corpo-invalido)
        (resposta-json {:error "Corpo da requisição não é um JSON válido"} 400)

        (and (some? titulo-bruto) (not (string? titulo-bruto)))
        (resposta-json {:error "Título deve ser texto"} 400)

        :else
        (let [titulo (some-> titulo-bruto str/trim)]
          (cond
            (str/blank? titulo)
            (resposta-json {:error "Título é obrigatório"} 400)

            (> (count titulo) tamanho-max-titulo)
            (resposta-json {:error (str "Título deve ter no máximo " tamanho-max-titulo " caracteres")} 400)

            :else
            (resposta-json {:success true :item (db/adicionar-item! titulo)} 201)))))
    (catch Exception e
      (resposta-json {:error (.getMessage e)} 500))))

(defn tratar-funcionalidades [_req]
  (resposta-json (flags/obter-todas-flags)))

(defn tratar-alternar-funcionalidade [req]
  (try
    (let [analisado (analisar-corpo-json req)
          flag (:flag analisado)
          habilitada (:enabled analisado)]
      (cond
        (or (= analisado corpo-invalido) (nil? analisado))
        (resposta-json {:error "Informe {\"flag\": nome, \"enabled\": true|false}"} 400)

        (not (flags/flag-conhecida? flag))
        (resposta-json {:success false :error (str "Flag " flag " não encontrada.")} 404)

        (not (boolean? habilitada))
        (resposta-json {:error "O campo 'enabled' deve ser true ou false"} 400)

        :else
        (resposta-json (flags/alternar-flag-local! flag habilitada))))
    (catch Exception e
      (resposta-json {:error (.getMessage e)} 500))))

(defn tratar-opcoes [_req]
  {:status 204
   :headers {"Access-Control-Allow-Origin" "*"
             "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
             "Access-Control-Allow-Headers" "Content-Type, Authorization"}})

;; Aliases para compatibilidade retroativa
(def fault-injected? falha-injetada?)
(def ready? pronto?)
(def start-time tempo-inicio)
(def get-hostname obter-hostname)
(def json-response resposta-json)
(def version-handler tratar-versao)
(def health-handler tratar-saude)
(def readiness-handler tratar-prontidao)
(def toggle-fault-handler tratar-alternar-falha)
(def list-items-handler tratar-listar-itens)
(def create-item-handler tratar-criar-item)
(def features-handler tratar-funcionalidades)
(def toggle-feature-handler tratar-alternar-funcionalidade)
(def options-handler tratar-opcoes)

(def aplicacao
  (ring/ring-handler
   (ring/router
    [["/api"
      ["/version" {:get tratar-versao :options tratar-opcoes}]
      ["/health" {:get tratar-saude :options tratar-opcoes}]
      ["/readiness" {:get tratar-prontidao :options tratar-opcoes}]
      ["/fault/toggle" {:post tratar-alternar-falha :options tratar-opcoes}]
      ["/items" {:get tratar-listar-itens
                 :post tratar-criar-item
                 :options tratar-opcoes}]
      ["/features" {:get tratar-funcionalidades :options tratar-opcoes}]
      ["/features/toggle" {:post tratar-alternar-funcionalidade :options tratar-opcoes}]]])
   (ring/create-default-handler)))

(def app aplicacao)

(defn -main [& _args]
  (let [porta (Integer/parseInt (or (System/getenv "PORT") "3000"))]
    (println (str "Iniciando backend Clojure na porta " porta "..."))
    (println (str "Versão: " (or (System/getenv "APP_VERSION") "v1.0.0")
                  " | Cor: " (or (System/getenv "APP_COLOR") "blue")))
    (let [servidor (jetty/run-jetty aplicacao {:port porta :join? false})]
      (println (str "Servidor ativo em http://0.0.0.0:" porta))
      ;; A conexão com o banco roda em paralelo: a porta já aceita requisições,
      ;; mas /api/readiness só fica verde quando o PostgreSQL responde.
      (future
        (loop []
          (if (db/inicializar-banco!)
            (reset! pronto? true)
            (do (Thread/sleep 5000)
                (recur)))))
      (.join servidor))))

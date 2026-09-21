(ns app.flags
  (:require [cheshire.core :as json])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.time Duration)))

;; Estado local das Feature Flags (Modo Integrado em Memória)
(def flags-locais
  (atom {"new_checkout"      {:enabled false :type "release"    :description "Nova tela de checkout em etapas"}
         "modern_layout"     {:enabled false :type "experiment" :description "Layout moderno com gradiente e novos cards"}
         "vip_discount"      {:enabled false :type "permission" :description "Cupom VIP de 20% para clientes selecionados"}
         "ops_degraded_mode" {:enabled false :type "ops"        :description "Desliga busca pesada durante pico de tráfego"}}))

(def url-unleash (or (System/getenv "UNLEASH_URL") "http://unleash:4242/api/client/features"))
(def token-unleash (or (System/getenv "UNLEASH_API_TOKEN") "*:development.9fbd851f2c38b0c178f2ed2e06e409b3035342442a41eb373eb35e17"))

;; Um único HttpClient por processo: cada inicialização cria thread seletora e pool próprios.
(def ^:private cliente-http
  (delay (.. (HttpClient/newBuilder)
             (connectTimeout (Duration/ofSeconds 2))
             build)))

(def ^:private ttl-cache-ms 10000)
(def ^:private estado-unleash (atom {:status "unknown"}))
(def ^:private ultima-atualizacao (atom 0))
(def ^:private atualizando? (atom false))

(defn- buscar-unleash []
  (try
    (let [requisicao (.. (HttpRequest/newBuilder)
                         (uri (URI/create url-unleash))
                         (header "Authorization" token-unleash)
                         (header "Accept" "application/json")
                         (timeout (Duration/ofSeconds 2))
                         GET
                         build)
          resposta   (.send @cliente-http requisicao (HttpResponse$BodyHandlers/ofString))]
      (if (= 200 (.statusCode resposta))
        {:status "connected"
         :data (json/parse-string (.body resposta) true)}
        {:status "error"
         :code (.statusCode resposta)}))
    (catch Exception e
      {:status "unavailable"
       :message (.getMessage e)})))

(defn- atualizar-unleash!
  "Atualiza o cache fora da thread da requisição. Quando o Unleash não está ativo,
  a resolução de DNS do host pode demorar segundos — tempo suficiente para o
  Nginx encerrar a chamada com 504 caso fosse executada de forma síncrona."
  []
  (let [agora (System/currentTimeMillis)]
    (when (and (> (- agora @ultima-atualizacao) ttl-cache-ms)
               (compare-and-set! atualizando? false true))
      (future
        (try
          (reset! estado-unleash (buscar-unleash))
          (finally
            (reset! ultima-atualizacao (System/currentTimeMillis))
            (reset! atualizando? false)))))))

(defn consultar-unleash []
  (atualizar-unleash!)
  @estado-unleash)

(defn flag-unleash
  "Localiza a flag no último snapshot recebido do Unleash. Só é considerada quando a conexão
  está ativa — se o servidor cair, o laboratório retorna sozinho ao modo local."
  [nome-flag]
  (when (= "connected" (:status @estado-unleash))
    (->> (get-in @estado-unleash [:data :features] [])
         (filter #(= nome-flag (:name %)))
         first)))

(defn origem-flag [nome-flag]
  (if (flag-unleash nome-flag) "unleash" "local"))

(defn habilitada?
  "Consulta usada pelos handlers para alterar o comportamento em tempo de execução.
  Modo dual: quando a mesma flag existe no Unleash, o servidor central prevalece —
  ele é a fonte da verdade. Sem o Unleash, prevalece o estado local em memória."
  [nome-flag]
  (if-let [remota (flag-unleash nome-flag)]
    (boolean (:enabled remota))
    (boolean (get-in @flags-locais [nome-flag :enabled]))))

(defn obter-todas-flags []
  (let [info-unleash (consultar-unleash)
        locais (reduce-kv (fn [acc nome dados]
                            (assoc acc nome (assoc dados
                                                   :enabled (habilitada? nome)
                                                   :effective (habilitada? nome)
                                                   :source (origem-flag nome))))
                          {}
                          @flags-locais)]
    {:mode "dual"
     :local locais
     :unleash {:configured (some? (System/getenv "UNLEASH_URL"))
               :connection (:status info-unleash)
               :features (get-in info-unleash [:data :features] [])}}))

(defn flag-conhecida? [nome-flag]
  (contains? @flags-locais nome-flag))

(defn alternar-flag-local! [nome-flag habilitada]
  (if (contains? @flags-locais nome-flag)
    (let [atualizado (swap! flags-locais assoc-in [nome-flag :enabled] (boolean habilitada))]
      {:success true :flag nome-flag :enabled (get-in atualizado [nome-flag :enabled])})
    {:success false :error (str "Flag " nome-flag " não encontrada.")}))

;; Aliases para compatibilidade retroativa
(def local-flags flags-locais)
(def unleash-url url-unleash)
(def unleash-token token-unleash)
(def query-unleash consultar-unleash)
(def unleash-flag flag-unleash)
(def flag-source origem-flag)
(def enabled? habilitada?)
(def get-all-flags obter-todas-flags)
(def known-flag? flag-conhecida?)
(def toggle-local-flag! alternar-flag-local!)

(ns app.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as sql]))

(def config-banco
  {:dbtype   "postgres"
   :dbname   (or (System/getenv "DB_NAME") "app_db")
   :host     (or (System/getenv "DB_HOST") "db")
   :port     (Integer/parseInt (or (System/getenv "DB_PORT") "5432"))
   :user     (or (System/getenv "DB_USER") "app_user")
   :password (or (System/getenv "DB_PASSWORD") "app_pass")})

;; O dashboard consome chaves simples (id, title, created_at). Sem esta
;; configuração, o next.jdbc devolve :items/id, :items/title... e o JSON chega no
;; React como "items/title", deixando a listagem de registros em branco.
(def opcoes-consulta {:builder-fn rs/as-unqualified-maps})

;; Armazenamento em memória de contingência caso o PostgreSQL esteja indisponível
(def itens-em-memoria (atom [{:id 1 :title "Primeiro Item (Demonstração)" :created_at (str (java.time.Instant/now))}]))
(def ^:private seq-em-memoria (atom 1))

(def ^:private fonte-dados (delay (jdbc/get-datasource config-banco)))

(defn obter-fonte-dados []
  (try
    @fonte-dados
    (catch Exception _
      nil)))

(defn- adicionar-em-memoria! [titulo]
  (let [item {:id (swap! seq-em-memoria inc)
              :title titulo
              :created_at (str (java.time.Instant/now))}]
    (swap! itens-em-memoria conj item)
    item))

(defn conectado?
  "Verificação real de prontidão (Readiness): só retorna verdadeiro quando o banco aceita consultas."
  []
  (boolean
   (try
     (when-let [ds (obter-fonte-dados)]
       (jdbc/execute-one! ds ["SELECT 1"] opcoes-consulta)
       true)
     (catch Exception _ false))))

(defn inicializar-banco!
  "Cria a tabela e o esquema, aguardando o PostgreSQL inicializar. Retorna verdadeiro quando conectado."
  ([] (inicializar-banco! 15 2000))
  ([tentativas atraso-ms]
   (loop [restantes tentativas]
     (let [resultado (try
                       (when-let [ds (obter-fonte-dados)]
                         (jdbc/execute! ds ["
                           CREATE TABLE IF NOT EXISTS items (
                             id SERIAL PRIMARY KEY,
                             title VARCHAR(255) NOT NULL,
                             created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                           );
                         "])
                         (println "Banco de dados inicializado com sucesso.")
                         true)
                       (catch Exception e
                         (println "Aguardando PostgreSQL..." (.getMessage e))
                         false))]
       (cond
         resultado true
         (<= restantes 1) (do (println "Aviso: prosseguindo com armazenamento em memória.") false)
         :else (do (Thread/sleep atraso-ms)
                   (recur (dec restantes))))))))

(def limite-padrao 20)

(defn listar-itens
  ([] (listar-itens limite-padrao))
  ([limite]
   (try
     (if-let [ds (obter-fonte-dados)]
       (jdbc/execute! ds ["SELECT id, title, created_at FROM items ORDER BY id DESC LIMIT ?" limite] opcoes-consulta)
       (take limite (reverse @itens-em-memoria)))
     (catch Exception _
       (take limite (reverse @itens-em-memoria))))))

(defn contar-itens []
  (try
    (if-let [ds (obter-fonte-dados)]
      (:count (jdbc/execute-one! ds ["SELECT COUNT(*) AS count FROM items"] opcoes-consulta))
      (count @itens-em-memoria))
    (catch Exception _
      (count @itens-em-memoria))))

(defn adicionar-item! [titulo]
  (try
    (if-let [ds (obter-fonte-dados)]
      (sql/insert! ds :items {:title titulo} opcoes-consulta)
      (adicionar-em-memoria! titulo))
    (catch Exception _
      (adicionar-em-memoria! titulo))))

;; Aliases para compatibilidade retroativa
(def db-config config-banco)
(def query-opts opcoes-consulta)
(def in-memory-items itens-em-memoria)
(def get-datasource obter-fonte-dados)
(def connected? conectado?)
(def init-db! inicializar-banco!)
(def default-limit limite-padrao)
(def list-items listar-itens)
(def count-items contar-itens)
(def add-item! adicionar-item!)

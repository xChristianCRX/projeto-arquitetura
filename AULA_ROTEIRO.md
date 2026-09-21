# Roteiro da Apresentação Prática: Estratégias de Deploy e Entrega Contínua

Este documento é o guia passo a passo para o apresentador conduzir a demonstração prática do laboratório. Ele contém exatamente **o que falar**, **os comandos a executar**, **o que mostrar no dashboard** e **o que acontece por trás dos panos no código e na infraestrutura**.

---

## 🧭 Visão Geral do Fluxo

```text
+---------------------+---------------------+---------------------+---------------------+---------------------+
|  PASSO 1            |  PASSO 2            |  PASSO 3            |  PASSO 4            |  PASSO 5            |
|  Setup & Visão Geral|  Rolling Update     |  Blue-Green Deploy  |  Canary & Caos      |  Feature Flags      |
|  Arquitetura Base   |  Readiness Probes   |  Rollback & Banco   |  Detecção de Falha  |  Deploy ≠ Release   |
+---------------------+---------------------+---------------------+---------------------+---------------------+
```

---

## 📍 PASSO 1: Abertura, Conceito e Subida do Ambiente

### 🗣️ O que falar aos ouvintes:
> *"Hoje vamos demonstrar na prática como a engenharia de software moderna resolveu as três grandes dores do modelo tradicional de deploy ('Big Bang'): a indisponibilidade (downtime), o risco concentrado (onde 100% dos usuários são impactados se houver um bug) e o rollback lento e estressante.*
>
> *Vamos ver em tempo real as 4 estratégias consagradas pela indústria: **Rolling Update**, **Blue-Green**, **Canary Release** e **Feature Flags**."*

### 💻 Comando a rodar no terminal:
```bash
make start
```

### ⚙️ O que acontece por trás dos panos no código:
1. O comando invoca `docker compose up -d --build`.
2. O Docker lê o arquivo [docker-compose.yml](file:///home/christian/repositorios/projeto-arquitetura/docker-compose.yml) e sobe 5 containers na rede virtual privada `lab-network`:
   * **`lab-proxy` (Nginx na porta 80):** O roteador e balanceador de carga central. Ele carrega a configuração [nginx/nginx.conf](file:///home/christian/repositorios/projeto-arquitetura/nginx/nginx.conf) e o upstream inicial [nginx/conf.d/upstream.conf](file:///home/christian/repositorios/projeto-arquitetura/nginx/conf.d/upstream.conf), que aponta 100% do tráfego da API para o serviço `api-blue:3000`.
   * **`lab-api-blue` (Clojure / Jetty na porta 3000):** A versão estável atual (**v1.0.0**, cor `blue`). Conecta-se ao PostgreSQL.
   * **`lab-api-green` (Clojure / Jetty na porta 3000):** A nova versão (**v2.0.0**, cor `green`), pronta para os testes.
   * **`lab-frontend` (React / Nginx interno):** A interface SPA que consome as APIs.
   * **`lab-postgres` (PostgreSQL 16):** Banco relacional compartilhado que roda o script [migration.sql](file:///home/christian/repositorios/projeto-arquitetura/backend/resources/db/migration.sql) na inicialização.

### 🖥️ O que mostrar na tela (`http://localhost`):
* Abra o navegador em **`http://localhost`**.
* Aponte para o **Painel de Tráfego em Tempo Real**: mostre que as requisições estão acontecendo a cada 600ms, todas recebendo **HTTP 200**, com **100% de sucesso na versão Blue (v1.0.0)** e exibindo o hostname do container no badge do topo.

---

## 📍 PASSO 2: Rolling Update (Atualização Gradual com Probes)

### 🗣️ O que falar aos ouvintes:
> *"O Rolling Update é a estratégia padrão do Kubernetes. Em vez de desligar todas as instâncias antigas e ligar as novas de uma só vez, nós substituímos as instâncias gradativamente.*
>
> *O ponto crítico aqui é: **como o balanceador sabe que a nova versão já pode receber usuários?** Se ele mandar tráfego antes da hora, o usuário toma erro 502 Bad Gateway. É aqui que entram os **Readiness Probes**."*

### 💻 Comando a rodar no terminal:
```bash
make rolling
```

### ⚙️ O que acontece por trás dos panos no código ([scripts/rolling-update.sh](file:///home/christian/repositorios/projeto-arquitetura/scripts/rolling-update.sh)):
1. **Passo 1 (Saúde da versão atual):**
   * O script executa: `docker compose exec -T proxy wget -q -O - http://api-blue:3000/api/health`.
   * O Clojure ([core.clj](file:///home/christian/repositorios/projeto-arquitetura/backend/src/app/core.clj#L44)) responde `{"status": "UP", "version": "v1.0.0"}` confirmando que a v1 está saudável.
2. **Passo 2 (Subida da nova versão em segundo plano):**
   * Executa `docker compose up -d --no-deps --build api-green`.
   * O container `lab-api-green` inicia em paralelo. **A versão Blue continua atendendo 100% dos usuários sem nenhum segundo de parada!**
3. **Passo 3 (O teste vital do Readiness Probe):**
   * O script entra em um loop `for i in 1..10; do`:
     * Faz requisições para `http://api-green:3000/api/readiness`.
     * No Clojure ([core.clj](file:///home/christian/repositorios/projeto-arquitetura/backend/src/app/core.clj#L52) e [db.clj](file:///home/christian/repositorios/projeto-arquitetura/backend/src/app/db.clj#L16)), o handler só devolve `HTTP 200 READY` quando a JVM subiu e a conexão com o PostgreSQL foi estabelecida com sucesso. Enquanto isso não ocorre, ele devolve `503 NOT_READY`.
     * O script só avança quando recebe `HTTP 200` da nova versão.
4. **Passo 4 (Transição gradual de tráfego no Nginx):**
   * Chama `./scripts/set-canary.sh 50`.
   * Copia [nginx/upstreams/canary-50.conf](file:///home/christian/repositorios/projeto-arquitetura/nginx/upstreams/canary-50.conf) para `nginx/conf.d/upstream.conf`.
   * Executa `docker compose exec -T proxy nginx -s reload`. Em menos de 50 milissegundos, o Nginx recarrega seus workers sem fechar conexões ativas e passa a dividir o tráfego em **50% Blue e 50% Green**.
5. **Passo 5 (Promoção a 100%):**
   * Chama `./scripts/set-canary.sh 100` (copia `green.conf` e recarrega o Nginx). Agora 100% do tráfego está na v2.0.0.

### 🖥️ O que mostrar na tela:
* Mostre a tabela de histórico e a barra colorida: os novos registros começam a intercalar verde e azul, e depois viram 100% verdes.
* Destaque: **"Vejam que a taxa de erros permaneceu em 0% e o total de requisições nunca parou. Isso é Zero Downtime Deployment."**

---

## 📍 PASSO 3: Blue-Green Deployment & Persistência de Dados

### 🗣️ O que falar aos ouvintes:
> *"No Blue-Green, mantemos dois ambientes de produção idênticos. Um está ativo atendendo o público e o outro fica em espera. A grande vantagem é a velocidade de **Rollback Instantâneo** se algo der errado.*
>
> *Mas existe um grande desafio arquitetural: **o Banco de Dados**. Se a v2 gravar dados no banco e precisarmos fazer rollback para a v1, esses dados serão perdidos? Vamos ver na prática."*

### 💻 Comandos a rodar no terminal:
```bash
# 1. Virada instantânea para Green (v2)
make switch-green
```

### ⚙️ O que acontece por trás dos panos:
* O script [scripts/switch-green.sh](file:///home/christian/repositorios/projeto-arquitetura/scripts/switch-green.sh) substitui o arquivo de upstream do Nginx por `green.conf` e roda `nginx -s reload`.
* O script [scripts/wait-color.sh](file:///home/christian/repositorios/projeto-arquitetura/scripts/wait-color.sh) aguarda os workers antigos do Nginx drenarem e valida que a API respondeu `"color": "green"`.

### 🖥️ Ação na tela (Demonstração de Persistência):
1. No card **"Persistência & Banco de Dados Compartilhado"**, digite: `Pedido de Compra #999` e clique em **Salvar**.
2. **O que acontece no código:** O React dispara `POST /api/items`. O Nginx manda para a `api-green`. O Clojure ([db.clj](file:///home/christian/repositorios/projeto-arquitetura/backend/src/app/db.clj#L46)) executa `sql/insert! ds :items {:title "Pedido de Compra #999"}` gravando a linha no PostgreSQL compartilhado.
3. Agora simule que a versão Green apresentou um problema grave e você precisa de um rollback imediato:
   ```bash
   make switch-blue
   ```
4. **O que acontece no código:** O script [scripts/switch-blue.sh](file:///home/christian/repositorios/projeto-arquitetura/scripts/switch-blue.sh) volta o Nginx para `blue.conf`. O tráfego volta a ser 100% atendido pela `v1.0.0` em milissegundos.
5. **Aponte na tela:** Clique no botão de atualizar (🔄) da listagem de itens: o item `Pedido de Compra #999` **continua lá intacto**.
6. **Conclusão para os ouvintes:** *"O banco de dados é compartilhado entre Blue e Green. Por isso, mudanças de schema em produção precisam seguir o padrão Expand-and-Contract (mudanças retrocompatíveis) para não quebrar a versão antiga em caso de rollback."*

---

## 📍 PASSO 4: Canary Release & Injeção de Caos (Chaos Engineering)

### 🗣️ O que falar aos ouvintes:
> *"A metáfora do Canary vem dos antigos mineradores de carvão que levavam um canário para a mina: como o pássaro é mais sensível a gases tóxicos, se ele passasse mal, os mineiros evacuavam antes de morrerem.*
>
> *No software, o Canary Release direciona uma fatia minúscula (ex: 10%) de usuários reais para a nova versão. Se houver um bug catastrófico, apenas 10% sofrem o impacto enquanto 90% continuam protegidos."*

### 💻 Comandos a rodar no terminal:
```bash
# 1. Aplicar roteamento ponderado de 10% para a versão v2
make canary-10
```

### ⚙️ O que acontece por trás dos panos no código:
1. O script [scripts/set-canary.sh](file:///home/christian/repositorios/projeto-arquitetura/scripts/set-canary.sh) copia o arquivo [nginx/upstreams/canary-10.conf](file:///home/christian/repositorios/projeto-arquitetura/nginx/upstreams/canary-10.conf):
   ```nginx
   upstream backend_upstream {
       server api-blue:3000 weight=9;
       server api-green:3000 weight=1;
   }
   ```
2. O Nginx distribui as requisições em round-robin ponderado: **a cada 10 requisições, exatamente 9 vão para a v1 e 1 vai para a v2**.
3. **Na tela:** Mostre a barra de tráfego indicando ~90% Blue e ~10% Green.

### 💻 Agora injete a falha no "Canário":
```bash
make fault-v2
```
*(ou clique no botão "Alternar Falha (HTTP 500)" no card do simulador de caos)*

### ⚙️ O que acontece por trás dos panos no código:
1. O script [scripts/toggle-fault.sh](file:///home/christian/repositorios/projeto-arquitetura/scripts/toggle-fault.sh) envia um `POST /api/fault/toggle` com `{"enabled": true}` para a réplica `api-green`.
2. No Clojure ([core.clj](file:///home/christian/repositorios/projeto-arquitetura/backend/src/app/core.clj#L70)), o atom em memória `fault-injected?` do container Green é setado para `true`.
3. Toda vez que o Nginx entrega uma requisição para a `api-green`, o handler ([core.clj](file:///home/christian/repositorios/projeto-arquitetura/backend/src/app/core.clj#L30)) intercepta o atom e retorna **HTTP 500**:
   ```json
   { "status": "error", "error": "Falha proposital ativada para simulacao de Canary!" }
   ```

### 🖥️ O que mostrar na tela:
* Mostre a barra de tráfego: a fatia de **Erros (5xx)** sobe exatamente para **~10%** em vermelho.
* Destaque a tabela: as chamadas Blue continuam respondendo `200 OK` (verde), enquanto apenas as chamadas Green falham com `500` (vermelho).
* **Explicação de impacto:** *"Se fosse um Big Bang Deploy, 100% da empresa estaria fora do ar. Com o Canary, 90% dos usuários nem perceberam o problema, permitindo que a equipe de SRE decida abortar o deploy (`make switch-blue`) com risco controlado."*

---

## 📍 PASSO 5: Feature Flags (Deploy Técnico vs Release de Negócio)

### 🗣️ O que falar aos ouvintes:
> *"Até agora, todas as decisões foram tomadas no nível da infraestrutura (Nginx e containers). As Feature Flags mudam de camada: a decisão é tomada **dentro do código da aplicação**.*
>
> *Isso permite separar dois conceitos fundamentais: **Deploy** (o código já está instalado e rodando nos servidores há dias) de **Release** (o momento em que a funcionalidade se torna visível ao usuário de negócio). Tudo isso em milissegundos, sem rebuild de container e sem novo deploy."*

### 🖥️ O que fazer na tela (no Card "Feature Flags"):

1. **Ative a flag `modern_layout` (*Experiment Toggle*):**
   * **O que acontece no código:** O React faz `POST /api/features/toggle` com `{"flag": "modern_layout", "enabled": true}`. O backend Clojure atualiza o atom `local-flags`.
   * **Efeito na tela:** A página inteira muda para um **fundo gradiente roxo/índigo** e ganha a etiqueta `Layout Moderno (A/B)`.
   * **Explicação:** *"Isso simula um teste A/B: podemos expor um design moderno para um grupo de usuários e medir se a conversão aumenta."*

2. **Ative a flag `vip_discount` (*Permission Toggle*):**
   * **Efeito na tela:** Surge o **card dourado de 20% OFF** com o cupom `ARQ20VIP`.
   * **Explicação:** *"Controla acesso por regras de negócio (ex: clientes VIP, plano Enterprise ou usuários beta)."*

3. **Ative a flag `new_checkout` (*Release Toggle*):**
   * **Efeito na tela:** Surge o bloco interativo do **Novo Checkout em 3 Etapas**.
   * **Explicação:** *"O código dessa tela já estava em produção há semanas, mas permaneceu oculto até o lançamento oficial de marketing."*

4. **Ative a flag `ops_degraded_mode` (*Ops / Disjuntor*):**
   * **Efeito na tela:** Surge a **tarja amarela de alerta operacional** no topo.
   * **Explicação:** *"O botão de pânico para a Black Friday: desliga recursos pesados (como buscas pesadas ou relatórios) para salvar a infraestrutura do colapso durante um pico inesperado."*

### 💻 Demonstração Avançada (Unleash Oficial):
```bash
make start-unleash
```
* Mostre que o status no card muda para **`● Conectado`**.
* Abra **`http://localhost:4242`** (login `admin` / `unleash4all`).
* Mostre as flags criadas no projeto `default`.
* Ligue ou desligue uma flag no Unleash: mostre que o Unleash é o servidor central da empresa, e a aplicação em `http://localhost` obedece aos comandos dele em tempo real!

---

##  PASSO 6: Conclusão & Tabela Comparativa

Para fechar a apresentação com chave de ouro, apresente a síntese das 4 estratégias:

| Estratégia | Velocidade de Rollback | Custo de Infraestrutura | Granularidade | Onde atua? | Quando usar? |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Rolling Update** | Média (progressiva) | Baixo (1x réplicas) | Por pod/instância | Orquestrador (Kubernetes) | Padrão geral de microsserviços |
| **Blue-Green** | Instantânea (milissegundos) | Alto (2x ambientes) | Tudo ou nada (100%) | Balanceador / Proxy | Sistemas críticos onde downtime é inaceitável |
| **Canary** | Rápida | Médio | Fina (% de tráfego) | Roteamento Ponderado | Mudanças arriscadas que exigem observabilidade |
| **Feature Flags** | Instantânea | Baixo (1x) | Por usuário, plano ou regra | Dentro do Código (Lógica) | Desacoplar lançamento comercial de deploy técnico |

### 💻 Comandos de encerramento após a apresentação:
```bash
make stop         # Desliga todos os containers
make clean        # Remove containers e volumes caso queira resetar o banco
```

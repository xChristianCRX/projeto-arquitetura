#!/bin/sh
# Demonstração de Rolling Update gradual (Capítulo 3 do Guia)
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
COMPOSE="docker compose -f $ROOT/docker-compose.yml"

echo "=========================================================="
echo "    DEMONSTRAÇÃO DE ROLLING UPDATE (ATUALIZAÇÃO GRADUAL)  "
echo "=========================================================="
echo "Conceito: Em vez de trocar tudo de uma vez (Big Bang),"
echo "substituímos instâncias uma a uma, verificando a saúde"
echo "(readiness probe) antes de desligar as versões antigas."
echo "----------------------------------------------------------"

# Função auxiliar para disparar e exibir requisições de amostra no terminal
amostrar_requisicoes() {
  total=$1
  titulo=$2
  echo "  --> Amostra de $total requisições em tempo real ($titulo):"
  i=1
  while [ $i -le $total ]; do
    resp=$($COMPOSE exec -T proxy wget -q -O - http://127.0.0.1/api/version 2>/dev/null || echo "")
    versao=$(echo "$resp" | grep -o '"version":"[^"]*"' | cut -d'"' -f4)
    cor=$(echo "$resp" | grep -o '"color":"[^"]*"' | cut -d'"' -f4)
    host=$(echo "$resp" | grep -o '"hostname":"[^"]*"' | cut -d'"' -f4)
    
    if [ "$cor" = "blue" ]; then
      printf "      [%02d/%02d] \033[1;34m[BLUE]\033[0m  Versão: %-7s | Instância: %s\n" "$i" "$total" "$versao" "$host"
    elif [ "$cor" = "green" ]; then
      printf "      [%02d/%02d] \033[1;32m[GREEN]\033[0m Versão: %-7s | Instância: %s\n" "$i" "$total" "$versao" "$host"
    else
      printf "      [%02d/%02d] \033[1;31m[ERRO]\033[0m  Não foi possível obter resposta\n" "$i" "$total"
    fi
    i=$((i + 1))
    sleep 0.5
  done
  echo ""
}

echo "Passo 1: Verificando a saúde da versão atual..."
$COMPOSE exec -T proxy wget -q -O - http://api-blue:3000/api/health
echo ""
amostrar_requisicoes 4 "100% Blue - Estado Inicial"

echo "Passo 2: Subindo réplicas da nova versão em segundo plano..."
# Em Kubernetes seria: kubectl set image deployment/...
# No Docker Compose, realizamos o rebuild e restart com zero downtime
$COMPOSE up -d --no-deps --build api-green
echo ""

echo "Passo 3: Aguardando readiness probe da nova versão responder 200 OK..."
READY=0
for i in 1 2 3 4 5 6 7 8 9 10; do
  # O wget do busybox devolve exit code != 0 para qualquer status fora do 2xx
  if $COMPOSE exec -T proxy wget -q -O /dev/null http://api-green:3000/api/readiness 2>/dev/null; then
    echo "  [OK] Nova versão pronta para receber tráfego!"
    READY=1
    break
  fi
  echo "  Aguardando inicialização da nova réplica... ($i/10)"
  sleep 2
done

if [ "$READY" != "1" ]; then
  echo "  [ABORTADO] A readiness probe não ficou verde: o tráfego continua 100% na versão antiga."
  echo "  É exatamente assim que o Kubernetes impede um rollout defeituoso de avançar."
  exit 1
fi
echo ""

echo "Passo 4: Atualizando o balanceador para incluir a nova versão gradualmente (50% / 50%)..."
sh "$ROOT/scripts/set-canary.sh" 50
echo "  Observe as duas versões convivendo em produção simultaneamente:"
amostrar_requisicoes 10 "Convivência 50% Blue / 50% Green"
echo "  Aguardando 4 segundos antes da promoção final..."
sleep 4
echo ""

echo "Passo 5: Concluindo a transição para 100% da nova versão..."
sh "$ROOT/scripts/set-canary.sh" 100
amostrar_requisicoes 4 "100% Green - Rollout Concluído"

echo "=========================================================="
echo "Rolling Update concluído com ZERO DOWNTIME no dashboard!"
echo "=========================================================="

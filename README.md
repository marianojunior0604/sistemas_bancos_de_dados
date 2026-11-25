# H2 Studio

Gerenciador visual de bancos de dados **H2**, no estilo MySQL Workbench / phpMyAdmin,
feito em Java 21 + JavaFX. A partir da versão 1.1 também conecta em **PostgreSQL, MySQL,
MariaDB, Firebird (2.5 a 5), SQL Server e SQLite** (estrutura, consulta e edição dos dados e
editor SQL, backup e restauração).

## Etapa 1 — o que já funciona

- **Abre qualquer versão do H2 sem estragar o arquivo.** O formato é lido no cabeçalho do arquivo
  e o driver certo é carregado em um ClassLoader separado:

  | Arquivo                       | Driver usado |
  |-------------------------------|--------------|
  | `.mv.db` formato 1 (H2 1.4.x) | 1.4.200      |
  | `.mv.db` formato 2 (H2 2.0/2.1) | 2.1.214    |
  | `.mv.db` formato 3 (H2 2.2+)  | 2.3.232      |
  | `.h2.db` (PageStore antigo)   | 1.4.200      |
  | Criptografado (`CIPHER=AES`)  | tenta 2.3 → 2.1 → 1.4 |

  Um H2 mais novo **não** abre arquivos de versões anteriores (erro 90048). Por isso o
  programa nunca usa um driver de outra família.
- **Vários bancos abertos ao mesmo tempo**, com uma árvore de conexões, schemas, tabelas e views.
  Há também um filtro por nome de tabela (Ctrl+T).
- **Grade de dados**:
  - paginação de 50 a 1000 linhas;
  - ordenação clicando no cabeçalho, feita no banco e não só na página;
  - busca rápida em todas as colunas e filtro SQL (`WHERE`);
  - "filtrar por este valor" e "ver valor completo";
  - copiar células com Ctrl+C ou linhas com cabeçalho (cola direto no Excel).
- **Estrutura**: colunas, tipos, NOT NULL, padrão, auto incremento, PK e FK.
- **Índices e chaves**: índices e chaves estrangeiras nas duas direções (quem esta tabela
  referencia e quem a referencia), com ON UPDATE / ON DELETE.
- **DDL** da tabela gerado pelo próprio H2 (`SCRIPT`).
- **Banco em uso pelo sistema**: o programa detecta o arquivo travado e oferece duas opções:
  tentar de novo depois de fechar o sistema, ou abrir uma **cópia somente leitura** na hora.
- **Modos de abertura**: Normal, Somente leitura e AUTO_SERVER.
- **Opções da URL**: bancos criados com, por exemplo, `MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`
  precisam das mesmas opções ao abrir no H2 2.x. Sem elas, a abertura falha com
  `Schema "public" not found`. O H2 Studio lê o arquivo, percebe que os nomes estão em
  minúsculas e acrescenta `DATABASE_TO_LOWER=TRUE` sozinho. Outras opções podem ser
  digitadas no campo **Opções da URL** e ficam salvas na conexão.
- **Segurança**:
  - sempre usa `IFEXISTS=TRUE`, então nunca cria um banco vazio por engano;
  - nesta etapa o programa ainda não grava dados.
- **Conexões recentes**, arrastar e soltar arquivos `.mv.db` / `.h2.db` e "Abrir com…"
  (o caminho do arquivo pode ser passado como argumento).

## Etapa 2 — edição

- **Editar direto na grade**: comece a digitar sobre a célula ou use F2 / Enter.
  - Enter confirma, Tab vai para a próxima coluna, Esc cancela.
  - Booleanos: lista true / false / NULL.
  - Texto longo ou com várias linhas (CLOB): abre um editor próprio, que carrega o texto
    completo do banco.
  - Chave estrangeira: botão "…" ou F4 abre a tabela relacionada com busca.
  - BLOB: botão direito › Carregar arquivo… / Salvar em arquivo….
- **Formatos aceitos**:
  - números `1.234,56` ou `1234.56`;
  - datas `31/12/2026` ou `2026-12-31`, além de `hoje` e `agora`;
  - booleanos `sim/não` ou `true/false`.
  - Tamanho do texto, precisão de DECIMAL, intervalo de inteiros e NOT NULL são validados
    antes de ir ao banco.
- **Linhas**: Nova linha (Insert), Duplicar (Ctrl+D), Excluir (Delete), Definir NULL
  (Shift+Delete), Restaurar valor original (Ctrl+Z). Colunas de identidade, padrão e calculadas
  aparecem como "(auto)" ou "(padrão)" e são preenchidas pelo banco.
- **Alterações pendentes**:
  - célula alterada em amarelo, linha nova em verde, linha excluída riscada em vermelho;
  - a aba ganha um "•";
  - o botão Salvar mostra quantas linhas mudaram.
- **Salvar (Ctrl+S)**:
  - grava tudo numa única transação (exclusões, depois alterações, depois inclusões);
  - se qualquer comando falhar, nada é gravado e a linha com problema fica marcada;
  - os erros aparecem em português (valor duplicado, referência inexistente, registro usado em
    outra tabela, coluna obrigatória...).
- **Backup automático** com o comando `BACKUP TO` do H2, antes do primeiro salvamento de cada
  conexão. Ficam os 10 mais recentes em `%USERPROFILE%\.h2studio\backups\<banco>\`.
  O menu Ferramentas tem: Fazer backup agora, Abrir pasta de backups e ligar/desligar o
  backup automático.
- **Proteção contra perder alterações**: trocar de página, filtrar, ordenar, atualizar, fechar a
  aba, desconectar ou sair perguntam se você quer Salvar / Descartar / Cancelar.
- **Tabelas sem chave primária** são editadas pelo `_ROWID_` interno do H2.
- **Somente consulta**: views, cópias de bancos travados e bancos abertos em modo somente leitura.

**Para restaurar um backup**: feche o sistema que usa o banco e extraia o arquivo `.mv.db` do
`.zip` por cima do original.

## Etapa 3 — editor SQL

Para abrir: **Nova consulta SQL** (Ctrl+N) na barra, botão direito no banco › Nova consulta SQL,
ou botão direito numa tabela › **Consultar com SQL**, que já executa um `SELECT … LIMIT 100`.

- **Realce de sintaxe**: palavras-chave, tipos, funções, textos, números, comentários e nomes
  das tabelas do banco. Tem números de linha.
- **Autocompletar**: Ctrl+Espaço a qualquer momento. A lista abre sozinha depois de
  `FROM / JOIN / UPDATE / INTO` (tabelas) e depois de `apelido.` (colunas da tabela daquele
  apelido). Entende os aliases do FROM/JOIN e schemas (`FINANCEIRO.`), e põe aspas quando o
  nome precisa (inclusive em bancos com `DATABASE_TO_LOWER`).
- **Executar**:
  - Ctrl+Enter executa a instrução sob o cursor (ou o texto selecionado);
  - Ctrl+Shift+Enter executa o script inteiro, parando no primeiro erro, a não ser que
    "Continuar após erro" esteja marcado em Opções;
  - cada consulta abre uma aba de resultado (com "Máx. linhas", padrão 1000);
  - Parar interrompe consultas demoradas.
- **Erros**: a mensagem vem em português e o ponto do erro fica sublinhado em vermelho no texto,
  com o cursor levado até lá. Duplo clique na mensagem também leva ao erro.
- **Transação própria**: cada aba SQL tem sua conexão. Com o **Auto-commit** desligado, as
  alterações ficam invisíveis para a grade e para o sistema até o **Commit**, e o **Rollback**
  desfaz. Fechar a aba ou o programa com transação aberta pede confirmação.
- **Segurança**:
  - DELETE/UPDATE sem WHERE, DROP, TRUNCATE, ALTER … DROP e SHUTDOWN pedem confirmação
    (o botão padrão é Cancelar);
  - o backup automático é feito antes da primeira instrução que altera o banco;
  - depois de CREATE/ALTER/DROP, a árvore e o autocompletar são atualizados.
- **Plano** (Ctrl+E): mostra o EXPLAIN da consulta.
- **Formatar** (Ctrl+Shift+F): uma cláusula por linha, palavras-chave em maiúsculas. Textos,
  comentários e nomes entre aspas não são alterados.
- **Comentar** (Ctrl+/): comenta ou descomenta as linhas selecionadas.
- **Histórico** por banco, com busca e "Inserir no editor". Fica em
  `%USERPROFILE%\.h2studio\historico\` e é um arquivo `.sql` comum.
- **Rascunho**: a primeira aba SQL de cada banco salva o texto sozinha e o recupera na próxima
  vez. Também dá para abrir e salvar arquivos `.sql` (Ctrl+S na aba SQL).

## Etapa 4 — estrutura

- **Nova tabela**: botão direito no banco ou no schema › Nova tabela… (ou Ferramentas › Nova tabela).
  Ela já vem com uma coluna `ID BIGINT` de auto incremento como chave primária.
- **Alterar estrutura**: botão direito na tabela › Alterar estrutura…, ou o botão na aba Estrutura
  da tabela. O designer tem quatro abas:
  - **Colunas**: PK, nome, tipo, tamanho, decimais, obrigatório (NOT NULL), auto incremento,
    padrão e comentário. Coluna nova aparece em verde, alterada em amarelo, removida em vermelho.
  - **Índices**: normais ou únicos, com várias colunas. Os automáticos (de PK e FK) aparecem só
    para consulta.
  - **Chaves estrangeiras**: colunas, tabela referenciada (a chave primária dela é escolhida
    sozinha), ON DELETE / ON UPDATE.
  - **SQL**: o script exato que será executado, atualizado enquanto você edita.
- **Validação antes de aplicar**: nomes inválidos ou repetidos, tipo sem tamanho válido, índice ou
  chave usando coluna que será removida...
- **Avisos**:
  - remoção de coluna (perde os dados);
  - redução de tipo (pode falhar);
  - coluna que passa a ser obrigatória;
  - nova coluna obrigatória sem padrão (só funciona com a tabela vazia).
- **Nomes**: seguem o jeito do banco (MAIÚSCULAS no padrão do H2, minúsculas com
  `DATABASE_TO_LOWER`), então o sistema continua usando os nomes sem aspas.
- **Aplicar no banco**: mostra os comandos e os avisos, faz backup e executa. No H2 cada comando de
  estrutura é gravado na hora; se um falhar, a mensagem diz quais já foram aplicados. Depois de
  aplicar, a árvore, as abas abertas da tabela e o autocompletar do editor SQL se atualizam.
- **Salvar como migração Flyway**: gera `V{próxima}__alterar_tabela_x.sql` (a versão é calculada
  a partir do `flyway_schema_history`) para colocar em `src/main/resources/db/migration`.
  Em bancos com Flyway, o designer avisa para escolher **um** caminho: aplicar aqui ou salvar a
  migração. Fazer os dois faz a migração falhar no próximo início do sistema.
- **Excluir tabela**:
  - mostra quantas linhas serão apagadas e quais tabelas dependem dela;
  - exige digitar o nome da tabela;
  - se outras tabelas a referenciam, é preciso marcar CASCADE;
  - faz backup antes.
- **Histórico do Flyway** (botão direito no banco ou menu Ferramentas): migrações aplicadas,
  falhas em vermelho e a próxima versão sugerida.

A sintaxe gerada (`SET DATA TYPE`, `SET/DROP NOT NULL`, `GENERATED BY DEFAULT AS IDENTITY`...) foi
testada no H2 1.4.200, 2.1.214 e 2.3.232.

## Etapa 5 — utilitários

Ficam em **Ferramentas**, no botão direito do banco › **Utilitários** e no botão direito da tabela.

- **Exportar (Excel ou CSV)**: pelo botão "Exportar…" da grade (respeita filtro, busca e
  ordenação atuais), pelo botão direito na tabela, ou pelo botão "Exportar…" de um resultado
  do editor SQL. No editor, a consulta roda de novo sem o limite de linhas.
  - **Excel (.xlsx)**: números como número, datas como data (dd/mm/aaaa), valores com 2 casas
    formatados, cabeçalho destacado, primeira linha congelada e filtro ligado. Não usa
    biblioteca externa e grava linha a linha, então aguenta tabelas grandes.
  - **CSV**: "Excel em português" (`;`, vírgula decimal, dd/mm/aaaa, UTF-8 com BOM) ou
    "Padrão internacional" (`,`, ponto decimal, datas ISO) ou separado por TAB.
  - Mostra o progresso e pode ser cancelado; um arquivo parcial nunca fica para trás.
- **Importar CSV**: pelo botão "Importar CSV…" da grade ou pelo botão direito na tabela.
  - Detecta a codificação (UTF-8 ou Windows-1252 do Excel antigo) e o separador.
  - Liga as colunas do arquivo às da tabela pelo nome, sem ligar para maiúsculas, acentos e
    espaços. "Data de Nascimento" → NASCIMENTO. Dá para ajustar a ligação à mão.
  - Aceita `1.234,56`, `31/12/2026`, `sim/não`. Vazio vira NULL ou o valor padrão / auto
    incremento da coluna.
  - **Valida tudo antes de gravar** e mostra os erros por linha do arquivo. Com qualquer erro,
    nada é gravado.
  - Grava numa única transação, com backup antes. Modos: adicionar, ou substituir (apaga as
    linhas atuais; avisa se outras tabelas têm ON DELETE CASCADE apontando para esta).
  - Se o CSV trouxer os IDs, o contador do auto incremento é ajustado, e o sistema não tenta
    repetir um ID depois.
- **Backups e restauração**: lista os backups do banco (automáticos, manuais e cópias de
  segurança), faz backup na hora e restaura um backup ou qualquer `.zip`/`.mv.db`/`.h2.db`.
  Antes de restaurar, o banco atual é guardado como "Cópia antes de restaurar", então dá para
  desfazer restaurando essa cópia.
- **Compactar banco**: `SHUTDOWN COMPACT`, com backup antes. Nos testes, os bancos caíram para
  cerca de 1/3 do tamanho (um PageStore antigo foi de 5 MB para 144 KB).
- **Converter versão do H2**: 1.4 → 2.1 / 2.3, 2.1 → 2.3, inclusive PageStore `.h2.db`,
  bancos criptografados e bancos com `DATABASE_TO_LOWER`. O H2 antigo exporta tudo (SCRIPT), o
  novo cria o banco e executa o script (`FROM_1X` para 1.4), e o H2 Studio **confere a quantidade
  de linhas de cada tabela**. Dá para:
  - criar um arquivo novo (`loja_h2-2.3.mv.db`, sem alterar o original); ou
  - substituir o original, que vai para os backups como "Original antes da conversão".

  **Depois de converter, o sistema precisa usar o H2 da nova versão** (no `pom.xml`). Com o H2
  antigo, ele não abre o arquivo convertido.
- **Gerar script SQL do banco**: `.sql` com estrutura e, se quiser, os dados. Serve como backup
  legível ou para recriar o banco com RUNSCRIPT.

Compactar, restaurar e substituir na conversão precisam do arquivo livre. O H2 Studio fecha
sozinho as abas, editores SQL e conexões daquele banco, faz a operação e reabre o banco. O
**sistema do cliente precisa estar fechado**; se não estiver, o H2 Studio avisa e não mexe em
nada.

## Multi-banco, parte 1 — conectar e navegar (versão 1.1)

**Arquivo › Conectar a servidor ou SQLite…** (Ctrl+Shift+O), o botão **Conectar…** da barra
ou o botão da tela inicial.

- **Tipos:** PostgreSQL, MySQL, MariaDB, Firebird 3/4/5, Firebird 2.5, SQL Server e SQLite.
  Porta, usuário e propriedades padrão mudam sozinhos ao trocar o tipo.
- **Firebird:** campo *Charset* (padrão `WIN1252`, o mais comum nos sistemas antigos em
  Delphi). Se os acentos aparecerem errados, troque para `UTF8`, `ISO8859_1` ou `NONE`.
  Firebird 2.5 tem tipo próprio porque usa outro driver (Jaybird 4).
- **SQLite:** escolha o arquivo ou **arraste o .db/.sqlite para a janela**. Um arquivo que não
  existe nunca é criado vazio.
- **Testar conexão** mostra a versão do servidor sem fechar o diálogo.
- **Somente leitura** bloqueia gravações no próprio banco (PostgreSQL e SQLite garantem isso
  na conexão).
- **Erros explicados:** senha errada, banco inexistente e servidor inacessível (desligado,
  firewall, porta) têm mensagem própria e o diálogo volta preenchido.
- **Recentes:** servidores e SQLite aparecem na tela inicial e em *Abrir recente*, com o tipo.
  O mesmo servidor/banco/usuário não se repete.

**Drivers:** nenhum vem dentro do programa. Na primeira conexão de cada tipo o H2 Studio
pergunta e baixa o jar do **Maven Central**, confere o SHA-1 publicado e guarda em
`%USERPROFILE%\.h2studio\drivers\`. Sem internet: copie o jar para essa pasta ou use
*Avançado › Usar .jar local…* (serve também para usar o MySQL Connector/J em vez do MariaDB).
*Ferramentas › Drivers instalados…* mostra o que já foi baixado.

| Tipo | Driver | Observação |
|---|---|---|
| PostgreSQL | pgjdbc 42.7.4 | |
| MySQL e MariaDB | MariaDB Connector/J 3.4.1 | LGPL; conecta nos dois. MySQL 8 usa `allowPublicKeyRetrieval=true` |
| Firebird 3, 4, 5 | Jaybird 5.0.6 | servidor Firebird (não o embutido) |
| Firebird 2.5 | Jaybird 4.0.10 + connector-api 1.5 | |
| SQL Server | mssql-jdbc 12.8.1 | `encrypt=false;trustServerCertificate=true` por padrão |
| SQLite | sqlite-jdbc 3.46.1.3 | |

**Navegação:**
- A árvore mostra o tipo ao lado do nome da conexão.
- PostgreSQL e SQL Server mostram os schemas. No MySQL/MariaDB cada banco do servidor é um
  item. No Firebird e no SQLite as pastas *Tabelas* e *Views* ficam direto na conexão.
- Em servidores com muitos bancos/schemas, só o que está em uso é lido ao conectar; os outros
  são lidos ao expandir.
- Ao abrir uma tabela, as abas **Estrutura** e **Índices e chaves** (com as FKs que chegam e as
  que saem) e o **DDL** funcionam como no H2. O DDL vem do próprio banco quando ele tem o
  comando: `SHOW CREATE TABLE` no MySQL, `sqlite_master` no SQLite e a definição das views. Nos
  outros casos é montado pelos metadados e marcado como aproximado.
- A aba **Dados** é a grade (veja a parte 2 abaixo).
- Importação de CSV, designer de tabelas, compactar/converter e o script SQL do banco continuam
  só para H2 por enquanto; para outro banco, o programa avisa.

## Multi-banco, parte 2 — grade com paginação e filtros (versão 1.2)

A aba **Dados** agora funciona em todos os bancos:

- **Paginação no jeito de cada banco:** `LIMIT/OFFSET` (PostgreSQL, MySQL/MariaDB, SQLite),
  `FIRST/SKIP` (Firebird) e `OFFSET/FETCH` (SQL Server 2012+). No SQL Server 2008 e anteriores,
  que não têm `OFFSET`, lê com `TOP` e pula as linhas no programa.
- **Busca rápida** em todas as colunas, sem diferenciar maiúsculas: `ILIKE` no PostgreSQL,
  `CONTAINING` no Firebird e `LIKE` nos outros. `%`, `_` e `[` digitados são procurados como
  texto, não como curinga.
- **Filtro SQL (WHERE)** com exemplo no dialeto do banco, **ordenação** clicando no cabeçalho e
  **filtrar pelo valor da célula** (datas no formato que cada banco aceita).
- **Texto longo:** abre o valor completo pela chave primária. Sem chave, mostra os primeiros
  2.000 caracteres.
- **Exportar** (Excel/CSV) da grade, com ou sem o filtro atual, e pelo menu da tabela na árvore.
  No PostgreSQL a exportação lê em partes, sem carregar a tabela inteira na memória.
- Datas inválidas do MySQL (`0000-00-00`) aparecem como texto em vez de dar erro.

## Multi-banco, parte 3 — edição de dados (versão 1.3)

A grade edita PostgreSQL, MySQL/MariaDB, Firebird, SQL Server e SQLite do mesmo jeito que no H2:
digitar na célula, Insert (nova linha), Ctrl+D (duplicar), Delete (excluir), Shift+Delete (NULL),
F4 (escolher o valor da chave estrangeira) e **Ctrl+S** grava tudo numa única transação. Se uma
linha falhar, nada é gravado e a linha com problema fica marcada.

- **Primeiro salvamento de cada conexão:** pergunta antes, porque nesses bancos o H2 Studio não faz
  backup automático. As alterações vão direto para o servidor.
- **Tabelas sem chave primária:** ficam só para consulta. Fora do H2 não existe um identificador
  interno seguro para dizer qual linha alterar.
- **Erros em português** para duplicado, chave estrangeira (pai inexistente ou registro em uso),
  campo obrigatório, tamanho, regra CHECK, registro bloqueado e deadlock, com o detalhe do
  servidor embaixo. Um registro bloqueado por outro usuário desiste em 30 segundos em vez de
  travar a tela.
- **PostgreSQL:** uuid, json/jsonb e enum aceitam o texto digitado (o servidor converte).
- **Firebird:** códigos preenchidos por gatilho com `GEN_ID`/`NEXT VALUE FOR` são reconhecidos como
  automáticos; deixe em branco na linha nova e o gatilho preenche.
- **SQLite:** datas gravadas como texto `aaaa-mm-dd` / `aaaa-mm-dd hh:mm:ss` e booleanos como 1/0,
  respeitando o tipo declarado na tabela; `INTEGER PRIMARY KEY` é automático.
- **MySQL com tabelas MyISAM:** esse motor não tem transação. Se uma linha falhar no meio, as
  anteriores já ficam gravadas.

## Multi-banco, parte 4 — editor SQL (versão 1.4)

**Nova consulta SQL** (Ctrl+N, menu da conexão na árvore ou *Consultar no editor SQL* no menu da
tabela) funciona em todos os bancos, com os mesmos recursos do H2: executar a instrução sob o
cursor ou tudo, várias abas de resultado, auto-commit ou transação própria, cancelar, histórico,
rascunho salvo, abrir/salvar `.sql` e exportar o resultado.

- **Scripts no padrão de cada banco:**
  - Firebird: `SET TERM ^ ;` … `SET TERM ; ^` (como no isql e no IBExpert);
  - MySQL/MariaDB: `DELIMITER $$` … `DELIMITER ;`;
  - SQL Server: linhas `GO`;
  - PostgreSQL: corpos `$$ … $$` / `$tag$ … $tag$`;
  - procedures, funções e gatilhos com `BEGIN … END` (inclusive `CASE … END`, `END IF`,
    `END WHILE`) também funcionam sem essas diretivas: os `;` do corpo não quebram a instrução.
- **Destaque de sintaxe e formatar** entendem `` `nome` `` e `#` comentário (MySQL), `[nome]` (SQL
  Server) e `$corpo$` (PostgreSQL).
- **Erro marcado no texto:** posição informada pelo PostgreSQL, linha/coluna do Firebird, trecho
  "near ..." do MySQL/SQLite/SQL Server e o nome da tabela ou coluna inexistente.
- **Autocompletar** com tabelas, colunas e schemas do banco. Coloca aspas só quando precisa, no
  estilo do banco: `"Nome"` no PostgreSQL, `` `nome` `` no MySQL, `[nome]` no SQL Server. Em
  servidores com mais de 5 schemas/bancos, lê só o que está em uso.
- **Plano (Ctrl+E):** `EXPLAIN` no PostgreSQL e no MySQL, `EXPLAIN QUERY PLAN` no SQLite. SQL
  Server e Firebird não têm plano por SQL e o botão fica desativado.
- **Proteção:** a primeira instrução que altera o banco (INSERT/UPDATE/DELETE/DDL) em cada
  conexão pede confirmação, porque não há backup automático nesses bancos. O aviso de
  DELETE/UPDATE sem WHERE e de DROP continua valendo.
- Procedures que devolvem contagens antes das linhas (SQL Server, MySQL) mostram o resultado.

## Multi-banco, parte 5 — backup e restauração (versão 1.5)

**Ferramentas › Backups e restauração…**, **Fazer backup agora** ou o menu da conexão na árvore.
Cada banco usa a ferramenta própria:

| Banco | Backup | Restauração | Arquivo |
|---|---|---|---|
| PostgreSQL | `pg_dump` (formato custom) | `pg_restore` numa transação só (ou `psql` para .sql) | `.backup` neste computador |
| MySQL / MariaDB | `mysqldump --single-transaction` + rotinas, gatilhos e eventos | `mysql` | `.sql.gz` neste computador |
| Firebird | `gbak -b` pela rede | `gbak -c` (novo) / `-rep` (substituir) | `.fbk` neste computador |
| SQL Server | `BACKUP DATABASE … COPY_ONLY` | `RESTORE DATABASE` como banco novo | `.bak` **no servidor** |
| SQLite | `VACUUM INTO` (sem ferramenta externa) | cópia do arquivo | `.sqlite` neste computador |

- **Onde ficam:** `%USERPROFILE%\.h2studio\backups\<tipo>-<banco>@<servidor>-xxxx\`. Também dá
  para *Salvar backup em…* qualquer pasta e *Restaurar de outro arquivo…*.
- **Ferramentas:** o programa procura no PATH e nas pastas padrão dos instaladores (PostgreSQL\NN\bin,
  MySQL Server\bin, MariaDB, XAMPP, Firebird_N_N). Se não achar, o diálogo mostra *Indicar onde
  está…* e guarda o caminho. Avisa quando o pg_dump é mais antigo que o servidor.
- **Restaurar como banco novo** (padrão): cria o banco (ou outro arquivo no SQLite/Firebird) e
  oferece abrir em seguida. Nunca sobrescreve um banco que já existe.
- **Substituir o banco atual:** antes faz um backup de segurança (`…_antes-de-restaurar`), fecha
  as abas do banco, restaura e reabre. No PostgreSQL é tudo ou nada. No MySQL não há transação:
  se parar no meio, use o backup de segurança. O gbak recusa substituir com gente conectada. No
  SQL Server só há a opção de banco novo.
- **Senhas** não vão na linha de comando: PGPASSWORD / ISC_PASSWORD no ambiente do processo e,
  no MySQL, um arquivo de opções temporário apagado no fim.
- **Cancelar** interrompe a ferramenta; arquivo pela metade não fica na pasta.

## Multi-banco, parte 6 — alterar estrutura visualmente (versão 1.6)

O mesmo designer de tabela do H2 agora funciona em PostgreSQL, MySQL/MariaDB, Firebird (2.5 a 5),
SQL Server e SQLite: **botão direito na tabela › Alterar estrutura…**, botão **Alterar estrutura…**
na aba *Estrutura* da tabela, **Nova tabela…** no menu da conexão/schema ou em **Ferramentas**, e
**Excluir tabela…**. A aba SQL mostra exatamente o que vai rodar, no dialeto do banco.

| Banco | Auto incremento | Renomear coluna | Mudar tipo | Se um comando falhar |
|---|---|---|---|---|
| PostgreSQL | `GENERATED BY DEFAULT AS IDENTITY` | `RENAME COLUMN` | `TYPE … USING col::tipo` | transação desfeita, nada muda |
| MySQL / MariaDB | `AUTO_INCREMENT` (precisa ser a PK) | `CHANGE COLUMN` (coluna inteira) | `CHANGE COLUMN` | os anteriores já ficaram |
| Firebird 3+ | `GENERATED BY DEFAULT AS IDENTITY` | `ALTER COLUMN … TO` | `ALTER COLUMN … TYPE` | os anteriores já ficaram |
| Firebird 2.5 | generator + gatilho `BEFORE INSERT` | `ALTER COLUMN … TO` | `ALTER COLUMN … TYPE` | os anteriores já ficaram |
| SQL Server | `IDENTITY(1,1)` | `sp_rename` | `ALTER COLUMN tipo NULL/NOT NULL` | transação desfeita, nada muda |
| SQLite | `INTEGER PRIMARY KEY AUTOINCREMENT` | `RENAME COLUMN` | recria a tabela | transação desfeita, nada muda |

- **Lista de tipos** de cada banco (ex.: `NVARCHAR(MAX)` no SQL Server, `BLOB SUB_TYPE TEXT` no
  Firebird, `TEXT`/`REAL` no SQLite). Tipos lidos do banco são normalizados (`int4` → `INTEGER`,
  `bpchar` → `CHAR`…).
- **SQLite:** o que o `ALTER TABLE` dele não faz (mudar tipo, obrigatório, padrão, chave, remover
  coluna com índice) é feito recriando a tabela: cria `<tabela>__h2studio_novo`, copia os dados,
  apaga a antiga, renomeia e recria os índices — tudo numa transação. Gatilhos precisam ser recriados.
- **SQL Server:** o valor padrão vira uma constraint `df_<tabela>_<coluna>`; ao mudar ou tirar o
  padrão, a constraint antiga é localizada e removida antes. O script copiado/salvo usa `GO`.
- **Firebird:** nomes limitados a 31 caracteres no 2.5; o 2.5 não muda obrigatoriedade de coluna
  existente pelo designer (o programa avisa). Renomear tabela não existe no Firebird (bloqueado com
  explicação). O script usa `SET TERM` em volta do gatilho.
- **Comentários:** PostgreSQL/Firebird `COMMENT ON`, MySQL no próprio `ALTER TABLE`; SQL Server e
  SQLite não têm comentário simples (aviso).
- **Excluir tabela:** pede para digitar o nome; com dependentes, usa `CASCADE` no PostgreSQL e,
  nos outros, remove antes as chaves estrangeiras que apontam para ela.
- **Backup:** nos bancos servidor/SQLite não há backup automático antes de aplicar (como no H2):
  a confirmação lembra de usar *Backup e restauração* quando a tabela tiver dados importantes.
- **Flyway:** se a tabela `flyway_schema_history` estiver no schema/banco em uso, *Salvar como
  migração…* continua disponível, igual ao H2.

## Multi-banco, parte 7 — converter um banco em outro (versão 1.7)

**Ferramentas › Converter para outro banco…**, **Converter para outro banco…** no menu da conexão
na árvore, ou **Copiar para outro banco…** no menu de uma tabela (já vem só ela marcada).

**Origem:** qualquer banco aberto (H2, PostgreSQL, MySQL/MariaDB, Firebird, SQL Server, SQLite);
escolha o schema e marque as tabelas. **Destinos:**

| Destino | O que acontece |
|---|---|
| Outro banco aberto | cria as tabelas no schema/banco escolhido e copia os dados |
| Novo arquivo SQLite | cria o arquivo, converte e abre na árvore |
| Novo banco H2 | cria o `.mv.db` (driver H2 mais novo, usuário `sa` sem senha), converte e abre |
| Script SQL (.sql) | estrutura + INSERTs no dialeto escolhido, para rodar no psql, mysql, isql, SSMS… |
| Planilha Excel (.xlsx) | uma aba por tabela e uma aba "Resumo" |

**Analisar** mostra antes de converter: como cada tabela e coluna fica no destino (tipo na origem →
tipo no destino), o SQL da estrutura e os avisos. **Converter** pede confirmação e mostra o
andamento (dá para cancelar entre lotes) e, no fim, um relatório por tabela.

Ordem usada: cria as tabelas sem índices nem chaves → copia os dados em lotes de 500 (com commit
a cada lote) → acerta o próximo número do auto incremento → cria os índices → cria as chaves
estrangeiras. Assim a ordem das tabelas não importa e a carga é rápida.

- **Tipos:** cada coluna passa por um "tipo comum" e vira o equivalente do destino. Exemplos:
  `BLOB SUB_TYPE TEXT` ↔ `TEXT`/`LONGTEXT`/`CLOB`/`NVARCHAR(MAX)`; `BOOLEAN` ↔ `BIT` (SQL Server) e
  `SMALLINT` 1/0 (Firebird 2.5); `DATETIME` ↔ `TIMESTAMP`/`DATETIME2`; `BYTEA` ↔ `BLOB`/`LONGBLOB`/`VARBINARY(MAX)`;
  `UUID` ↔ `UNIQUEIDENTIFIER` ou `CHAR(36)`; `JSON`/`JSONB` ↔ `JSON` ou texto. Tipos sem equivalente
  (ENUM, INTERVAL, arrays…) viram texto, com aviso. Entre bancos do mesmo tipo o tipo original é mantido.
- **Limites de cada banco:** VARCHAR grande demais vira texto longo (Firebird 8.191, SQL Server 4.000,
  MySQL 16.383 caracteres); no MySQL, se a soma dos VARCHAR passar de 64 KB por linha, os maiores
  viram TEXT; DECIMAL acima de 18 dígitos no Firebird é reduzido; nomes com mais de 31 caracteres
  no Firebird são encurtados. Texto sem limite usado em índice (comum no PostgreSQL) vira VARCHAR do
  tamanho do maior valor, para o índice poder ser criado. Tudo isso aparece nos avisos.
- **Auto incremento:** generator + gatilho do Firebird antigo, SERIAL/IDENTITY, AUTO_INCREMENT e
  INTEGER PRIMARY KEY do SQLite viram o auto incremento do destino. Os ids são copiados como estão
  (o registro 0 também) e o próximo número continua depois do maior.
- **Valores padrão:** números, textos, verdadeiro/falso e data/hora atual (`now()`, `GETDATE()`,
  `'NOW'`, `'TODAY'`, `CURRENT_TIMESTAMP`…) são traduzidos; funções próprias de um banco são
  deixadas de fora, com aviso.
- **Nomes:** no PostgreSQL ficam em minúsculas, no Firebird/H2 em maiúsculas; no MySQL, SQL Server
  e SQLite você escolhe (manter, minúsculas ou MAIÚSCULAS). Espaços e símbolos viram `_`;
  no Firebird os acentos são removidos.
- **Dados:** datas do SQLite (texto ou número) são interpretadas; datas inválidas (ex.: `0000-00-00`
  do MySQL) e textos maiores que a coluna viram NULL/são cortados, com a contagem no relatório. Se
  o destino recusar uma linha, a tabela para ali, as anteriores ficam gravadas e o relatório mostra
  a linha recusada; as outras tabelas continuam.
- **Tabelas que já existem no destino:** a conversão para e avisa, a menos que você marque
  *Substituir* (apaga e recria, removendo antes as chaves que apontam para elas).
- **Não convertidos:** views, gatilhos, procedures e generators avulsos (o SQL deles é da linguagem
  de cada banco). As views aparecem nos avisos para você recriar no destino.
- O banco de origem nunca é alterado.

## Backup do Firebird (.fbk) → banco (.fdb) (versão 1.10)

**Arquivo › Restaurar backup do Firebird (.fbk)…**, ou arraste o `.fbk` para a janela, ou dois cliques
/ "Abrir com › H2 Studio" no Windows. Não precisa de conexão aberta.

1. Escolha o `.fbk` (ou `.gbk`).
2. Servidor Firebird: normalmente o deste computador (`localhost`, porta 3050, SYSDBA). A lista
   *Usar* traz os servidores das conexões abertas e recentes, já com usuário e senha.
3. Banco novo: o `.fdb` é sugerido ao lado do `.fbk`. É um caminho **no computador do servidor**:
   com o Firebird nesta máquina é uma pasta daqui, e o serviço do Firebird precisa poder gravar nela.
4. **Restaurar**: o `gbak` monta o banco e o programa já abre na árvore.

- Precisa do `gbak` (vem com o Firebird; o programa procura nas pastas padrão ou em *Ferramentas do banco*).
- Backup do **Firebird 2.5** restaurado num servidor **3, 4 ou 5** já sai no formato novo: é o jeito
  mais simples de atualizar o banco antigo de um cliente.
- Nunca substitui um banco existente: se o `.fdb` já existir, peça outro nome.
- Erros comuns explicados: senha errada, servidor desligado, pasta sem permissão, arquivo que não é
  backup e backup de versão mais nova que o servidor.

## Importar CSV ou planilha (versão 1.9)

Funciona em **todos os bancos** (H2, PostgreSQL, MySQL/MariaDB, Firebird, SQL Server, SQLite).
Entradas: **Ferramentas › Importar planilha ou CSV…**, **Importar planilha ou CSV…** no menu da
conexão/schema, **Importar CSV ou planilha…** no menu da tabela e o botão **Importar…** na grade.

- **Arquivos:** `.xlsx` (Excel 2007+, LibreOffice, Google Planilhas) e `.csv`/`.txt` (UTF-8 ou
  Windows-1252, separador `;`, `,` ou TAB, detectados sozinhos). O `.xls` antigo (Excel 97–2003)
  não é lido: abra e salve como .xlsx. Na planilha você escolhe a aba; abas ocultas ficam de fora e
  fórmulas entram com o valor calculado.
- **Tabela nova:** o programa olha os dados e sugere nome e tipo de cada coluna, no dialeto do banco
  ("Data de Nascimento" → `data_de_nascimento DATE`, "Limite de Crédito" → `NUMERIC(15, 2)`,
  verdadeiro/falso → `BOOLEAN`, texto → `VARCHAR` do tamanho dos dados). Números com **zero à
  esquerda** (CPF, CEP, códigos) ficam como texto. Dá para desmarcar colunas, renomear e trocar o
  tipo; por padrão cria uma coluna `id` com auto incremento.
- **Tabela existente:** liga as colunas pelo nome (sem ligar para maiúsculas, acentos e espaços) e
  **adiciona** ou **substitui** as linhas.
- **Todas as abas de uma vez:** "Importar todas as abas…" cria uma tabela por aba (tipos
  automáticos); abas vazias são puladas e uma aba com problema não impede as outras.
- **Formatos brasileiros:** datas reais do Excel e texto como `24/11/2026` ou `2026-11-24`;
  números como `1.234,56`, `29,9` e `R$ 19,90`; sim/não.
- **Nada pela metade:** todas as linhas são conferidas antes (tipo, tamanho, obrigatório) e a
  gravação é numa transação só. Se o banco recusar uma linha (ex.: ID repetido), nada é gravado e
  aparece qual linha do arquivo foi recusada. Na tabela nova, se a importação falhar, a tabela
  criada é apagada.
- **IDs vindos do arquivo:** depois de importar, o próximo número do auto incremento passa do maior
  ID (PostgreSQL, H2 e Firebird 3+; MySQL, SQL Server e SQLite fazem isso sozinhos; no SQL Server
  usa `IDENTITY_INSERT`). No Firebird com generator + gatilho o generator precisa ser ajustado à mão.
- No H2, com o backup automático ligado, é feito um backup antes de importar.

## Como rodar

### NetBeans
Abra a pasta como projeto Maven e use **Clean and Build** e depois **Run**. O `nbactions.xml` já
aponta para `br.com.marianojunior.h2studio.Launcher`.

O build copia os três drivers H2 para `target/drivers` (plugin `maven-dependency-plugin`).

### PowerShell
```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\empacotar.ps1                 # programa + instalador (dist\instalador\H2Studio-<versão>-instalador.exe)
.\empacotar.ps1 -SemInstalador  # só dist\H2 Studio\H2 Studio.exe (com Java embutido, roda sem instalar)
.\empacotar.ps1 -SoJar          # dist\h2studio\h2studio.jar + drivers\
```
O script localiza sozinho o JDK 21 e o Maven do NetBeans quando o `mvn` não está no PATH.

## Instalador

Pré-requisito, uma vez só: **Inno Setup 6.3 ou mais novo** (grátis) —
`winget install JRSoftware.InnoSetup`. Depois, `.\empacotar.ps1` gera tudo:

1. compila com o Maven (`target\h2studio.jar` + drivers H2);
2. o **jpackage** monta `dist\H2 Studio` com o `H2 Studio.exe` e um Java 21 enxuto embutido
   (só os módulos que o programa e os drivers usam, levantados com `jdeps`: ~60 MB em vez de ~290 MB
   do JDK inteiro; inclui datas/números em português, charsets antigos como DOS850 do Firebird e
   criptografia para conexões com SSL);
3. o **Inno Setup** (`instalador\h2studio.iss`) gera `dist\instalador\H2Studio-<versão>-instalador.exe`.

A versão vem do `<version>` do `pom.xml` (é ela que vai no instalador, no "Sobre" e no Painel de
Controle). Para lançar uma versão nova: mude a versão no `pom.xml` e no `MainWindow.VERSION`,
rode o script e distribua o `.exe`.

O que o instalador faz:

- Em português, com a opção **"Para todos os usuários"** (pede administrador, vai para Arquivos de
  Programas) ou **"Só para mim"** (sem administrador, vai para `%LOCALAPPDATA%\Programs`) — útil
  em computador de cliente onde o usuário não é administrador.
- Atalho no menu Iniciar e, se marcado, na área de trabalho; opção de abrir o programa no fim.
- **Atualização:** rodar o instalador de uma versão nova por cima atualiza no mesmo lugar (o `AppId`
  não muda), fecha o H2 Studio se estiver aberto e troca o Java e os jars antigos. As conexões,
  o histórico e os backups (`%USERPROFILE%\.h2studio`) ficam intactos.
- **"Abrir com > H2 Studio"** para `.db` (os bancos H2 `banco.mv.db` são `.db` para o Windows), `.sqlite`
  e `.sqlite3`, sem tirar o programa padrão de ninguém; opcionalmente, dois cliques em `.sqlite`
  abrem direto no H2 Studio.
- **Uma janela só:** com o H2 Studio já aberto, abrir outro banco pelo Explorer abre na janela que
  já está aberta (a segunda execução entrega o arquivo e fecha). Para forçar outra janela:
  `-Dh2studio.novaJanela=true`.
- **Desinstalar** (Configurações › Aplicativos): remove o programa e pergunta — com "Não" como
  padrão — se apaga também a pasta `.h2studio` do usuário (conexões, drivers baixados e backups).

O instalador não é assinado digitalmente: na primeira vez o Windows SmartScreen mostra "O Windows
protegeu o computador" → *Mais informações* → *Executar assim mesmo*. Para tirar o aviso é preciso
um certificado de assinatura de código (assinar com `signtool` depois do Inno Setup).

## Onde ficam as coisas

| O quê | Onde |
|---|---|
| Conexões recentes | `%USERPROFILE%\.h2studio\conexoes.properties` |
| Drivers extras (ex.: `h2-2.2.224.jar`) e drivers baixados dos outros bancos | `%USERPROFILE%\.h2studio\drivers\` |
| Cópias temporárias de bancos travados | `%USERPROFILE%\.h2studio\copias\` (apagadas após 2 dias) |

A senha só é salva quando você marca **"Lembrar senha"**, e fica em texto simples
nesse arquivo.

## Estrutura do código

```
core/   (sem JavaFX — dá para reaproveitar numa versão web)
  DbType             tipos de banco: porta, usuário, URL, aspas, driver de cada um
  JdbcDrivers        baixa (Maven Central + SHA-1) e carrega os drivers dos outros bancos
  ServerConnector    abre PostgreSQL/MySQL/Firebird/SQL Server/SQLite e explica os erros
  DdlReader          DDL de tabelas e views nos outros bancos
  H2FileInspector    lê o cabeçalho e descobre a versão do arquivo
  DriverRegistry     encontra os jars h2-x.y.z.jar e carrega cada um isolado
  ConnectionFactory  monta a URL, abre, traduz erros, abre cópia de banco travado
  DbSession          conexão + thread exclusiva (a interface nunca trava)
  MetadataService    schemas, tabelas, colunas, índices, FKs, DDL (via JDBC DatabaseMetaData)
  TableDataService   leitura paginada com busca/filtro/ordenação no dialeto de cada banco
ui/
  MainWindow, DatabaseTree, TableTab, DataGrid, ConnectionDialog, WelcomePane
  ServerConnectionDialog, DriverSetup   conexão com os outros bancos e download do driver
```


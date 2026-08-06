const { Client } = require('pg');
const connectionString = process.env.DATABASE_URL;
if (!connectionString) {
  console.error('DATABASE_URL is required to run this script. Set it in your environment.');
  process.exit(1);
}
const client = new Client({ connectionString });
client.connect()
  .then(() => client.query('SELECT * FROM users ORDER BY updated_at DESC LIMIT 5'))
  .then(res => {
    console.table(res.rows);
    client.end();
  })
  .catch(err => {
    console.error(err);
    process.exit(1);
  });

![News Blog — A fresh perspective](docs/assets/readme-banner.webp)

<p align="center"><strong>Read. Write. Share a fresh perspective.</strong></p>
<p align="center">A modern news platform with a visual writing studio and local AI.</p>

## ✨ What you can do

- **Discover stories** — public news, category filters, search and article discussions.
- **Write visually** — format text, drag and drop images, choose a cover and preview your story. No HTML knowledge needed.
- **Create with AI** — generate Persian headlines, summaries, categories and full articles using local Ollama.
- **Manage your newsroom** — reporter workspace, admin publishing controls and comment moderation.
- **Control access** — USER, REPORTER and ADMIN roles, stateless JWT authentication and BCrypt password hashing.

## 🛠 Built with

**Java 17+ · Spring Boot · Spring Security · PostgreSQL · Tailwind CSS · Vanilla JavaScript · Ollama**

Images are stored in PostgreSQL as binary data and served through public URLs. The responsive UI uses locally compiled Tailwind CSS, with no CDN required to run the app.

## 🚀 Start locally

You need **Java 17+**, **PostgreSQL** and **Ollama** running on your computer.

```bash
git clone https://github.com/Tahanoa/News-Blog.git
cd News-Blog
ollama pull qwen3:4b-instruct
```

Run these commands in **Git Bash**. The default database is your existing local `postgres` database, with username `postgres`:

```bash
read -r -s -p 'PostgreSQL password: ' DB_PASSWORD
export DB_PASSWORD

# Create the first administrator using your own email and password.
export ADMIN_USERNAME='admin'
export ADMIN_EMAIL='you@example.com'
read -r -s -p 'Admin password (12+ characters): ' ADMIN_PASSWORD
export ADMIN_PASSWORD

./mvnw spring-boot:run -Dspring-boot.run.profiles=ai-local
```

Open **[localhost:8081](http://localhost:8081)**. Log in with your administrator credentials to manage users and publish stories. Public signup creates a USER; administrators can assign REPORTER access.

The local profile binds to `127.0.0.1`. Its signing key is generated on startup unless `JWT_SECRET` is set, so a server restart invalidates existing tokens. Remove the bootstrap environment variables after creating the administrator.

<img width="1200" height="2925" alt="news-blog-infographic (1)" src="https://github.com/user-attachments/assets/5c889c90-5c01-4468-ab3a-3f90cdb0ca39" />


## 🧭 Find your way

| Page | Purpose |
| --- | --- |
| `/` | Public news and search |
| `/news/{id}` | Full story, images and comments |
| `/reporter` | Visual editor, media library and AI assistant |
| `/admin` | Users, roles and account access |
| `/login` · `/register` | Account login and signup |

**Reading is public.** Commenting requires login. Reporters manage their own drafts; administrators control publication. Image URLs are public even for draft stories; uploads and deletion remain protected.

## 📖 Need more detail?

See the [project guide](docs/project-guide.md) for configuration, API endpoints, JWT behavior and PowerShell setup.

To rebuild the theme after changing its source:

```bash
npm ci
npm run build:css
```

Node.js is only needed to rebuild CSS. It is not required to run the application.

---

<p align="center">Built by <a href="https://github.com/Tahanoa">Tahanoa</a> · Stories worth your time.</p>

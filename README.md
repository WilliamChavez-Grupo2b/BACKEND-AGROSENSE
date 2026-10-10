# BACKEND-AGROSENSE

## Build the backend image

Run from the repository root so Docker can access both `agrosense/` and `database/`:

```powershell
docker compose build backend
```

Alternatively, build directly:

```powershell
docker build -f agrosense/Dockerfile -t agrosense-backend .
```

If your current directory is `agrosense\`, use `..` as the build context:

```powershell
docker build -f Dockerfile -t agrosense-backend ..
```

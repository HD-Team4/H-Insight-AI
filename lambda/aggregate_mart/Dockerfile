# KAN-76: 집계 Lambda 컨테이너 이미지.
# DuckDB는 네이티브 바이너리라 컨테이너 이미지로 패키징하는 게 가장 깔끔
# (Lambda 레이어 빌드보다 재현성↑). "로컬에서 쓰던 DuckDB를 Lambda에 그대로".
FROM public.ecr.aws/lambda/python:3.12

RUN pip install --no-cache-dir duckdb==1.1.3 boto3

COPY handler.py ${LAMBDA_TASK_ROOT}/

CMD ["handler.lambda_handler"]
